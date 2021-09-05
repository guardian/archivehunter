package services.datamigration.streamcomponents

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Source}
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDBAsync, AmazonDynamoDBClient}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, BatchWriteItemRequest, BatchWriteItemResult, DeleteRequest, PutRequest, WriteRequest}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration

import scala.concurrent.Await
import scala.util.Try
import scala.concurrent.duration._
import scala.collection.JavaConverters._

class LightboxUpdateSinkSpec extends Specification with Mockito {
  implicit val system = ActorSystem("LightboxUpdateSink")
  implicit val mat = Materializer.matFromSystem

  def stringValue(s:String) = new AttributeValue().withS(s)

  "LightboxUpdateSink" should {
    "commit an incoming item via batch write" in {
      val testAddItem = Map("pk"->stringValue("testitem"),"field"->stringValue("value"))
      val testDeleteItem = Map("pk"->stringValue("deleteitem"), "field"->stringValue("othervalue"))
      val testUpdate = UpdateRequest(
        testAddItem,
        testDeleteItem
      )

      val mockClient = mock[AmazonDynamoDBAsync]
      mockClient.batchWriteItem(any[BatchWriteItemRequest]) returns new BatchWriteItemResult()
        .withUnprocessedItems(Map[String, java.util.List[WriteRequest]]().asJava)

      val config = Configuration.empty
      val mockClientMgr = mock[DynamoClientManager]
      mockClientMgr.getClient(any) returns mockClient

      val result = Try {
        Await.result(
          Source
            .single(testUpdate)
            .toMat(LightboxUpdateSink("sometable", config, mockClientMgr))(Keep.right)
            .run(),
          10.seconds
        )
      }

      result must beSuccessfulTry

      val expectedWriteRequest = new WriteRequest().withPutRequest(new PutRequest().withItem(testAddItem.asJava))
      val expectedDeleteRequest = new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(testDeleteItem.asJava))

      there was one(mockClient).batchWriteItem(
        new BatchWriteItemRequest()
          .withRequestItems(
            Map("sometable"->List(
              expectedWriteRequest,
              expectedDeleteRequest
            ).asJava).asJava)
      )
    }

    "commit multiple batches of items" in {
      val testAddItem = Map("pk"->stringValue("testitem"),"field"->stringValue("value"))
      val testDeleteItem = Map("pk"->stringValue("deleteitem"), "field"->stringValue("othervalue"))
      val testUpdate = UpdateRequest(
        testAddItem,
        testDeleteItem
      )

      val mockClient = mock[AmazonDynamoDBAsync]
      mockClient.batchWriteItem(any[BatchWriteItemRequest]) returns new BatchWriteItemResult()
        .withUnprocessedItems(Map[String, java.util.List[WriteRequest]]().asJava)

      val config = Configuration.empty
      val mockClientMgr = mock[DynamoClientManager]
      mockClientMgr.getClient(any) returns mockClient

      val result = Try {
        Await.result(
          Source
            .fromIterator(()=>Seq.fill(47)(testUpdate).toIterator)
            .toMat(LightboxUpdateSink("sometable", config, mockClientMgr))(Keep.right)
            .run(),
          10.seconds
        )
      }

      result must beSuccessfulTry

      val expectedWriteRequest = new WriteRequest().withPutRequest(new PutRequest().withItem(testAddItem.asJava))
      val expectedDeleteRequest = new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(testDeleteItem.asJava))

      there were three(mockClient).batchWriteItem(  //the number is chosen so we should have exactly 3 commits to the database with no leftover
        new BatchWriteItemRequest()
          .withRequestItems(
            Map("sometable"->Seq.fill(12)(  //we limit to 24 items so fill with 12x 2 items
              Seq(expectedWriteRequest,
              expectedDeleteRequest)
            ).flatten.asJava).asJava)
      )
    }

    "bail if BatchWriteItems raises an exception" in {
      val testAddItem = Map("pk"->stringValue("testitem"),"field"->stringValue("value"))
      val testDeleteItem = Map("pk"->stringValue("deleteitem"), "field"->stringValue("othervalue"))
      val testUpdate = UpdateRequest(
        testAddItem,
        testDeleteItem
      )

      val mockClient = mock[AmazonDynamoDBAsync]
      mockClient.batchWriteItem(any[BatchWriteItemRequest]) throws new RuntimeException("Boo!")

      val config = Configuration.empty
      val mockClientMgr = mock[DynamoClientManager]
      mockClientMgr.getClient(any) returns mockClient

      val result = Try {
        Await.result(
          Source
            .fromIterator(()=>Seq.fill(47)(testUpdate).toIterator)
            .toMat(LightboxUpdateSink("sometable", config, mockClientMgr))(Keep.right)
            .run(),
          10.seconds
        )
      }

      result must beFailedTry
      result.failed.get.getMessage mustEqual("Boo!")
      val expectedWriteRequest = new WriteRequest().withPutRequest(new PutRequest().withItem(testAddItem.asJava))
      val expectedDeleteRequest = new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(testDeleteItem.asJava))

      there was one(mockClient).batchWriteItem(  //the number is chosen so we should have exactly 3 commits to the database with no leftover
        new BatchWriteItemRequest()
          .withRequestItems(
            Map("sometable"->Seq.fill(12)(  //we limit to 24 items so fill with 12x 2 items
              Seq(expectedWriteRequest,
                expectedDeleteRequest)
            ).flatten.asJava).asJava)
      )
    }

    "retry if unprocessed items are returned" in {
      val testAddItem = Map("pk"->stringValue("testitem"),"field"->stringValue("value"))
      val testDeleteItem = Map("pk"->stringValue("deleteitem"), "field"->stringValue("othervalue"))
      val testUpdate = UpdateRequest(
        testAddItem,
        testDeleteItem
      )

      val expectedWriteRequest = new WriteRequest().withPutRequest(new PutRequest().withItem(testAddItem.asJava))
      val expectedDeleteRequest = new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(testDeleteItem.asJava))

      val mockClient = mock[AmazonDynamoDBAsync]
      mockClient.batchWriteItem(any[BatchWriteItemRequest]) returns new BatchWriteItemResult()
        .withUnprocessedItems(Map[String, java.util.List[WriteRequest]]("sometable"->Seq(expectedDeleteRequest).asJava).asJava) thenReturns new BatchWriteItemResult()
        .withUnprocessedItems(Map[String, java.util.List[WriteRequest]]().asJava)

      val config = Configuration.empty
      val mockClientMgr = mock[DynamoClientManager]
      mockClientMgr.getClient(any) returns mockClient

      val result = Try {
        Await.result(
          Source
            .single(testUpdate)
            .toMat(LightboxUpdateSink("sometable", config, mockClientMgr))(Keep.right)
            .run(),
          10.seconds
        )
      }

      result must beSuccessfulTry

      there was one(mockClient).batchWriteItem(
        new BatchWriteItemRequest()
          .withRequestItems(
            Map("sometable"->List(
              expectedWriteRequest,
              expectedDeleteRequest
            ).asJava).asJava)
      ) andThen one(mockClient).batchWriteItem(
        new BatchWriteItemRequest()
          .withRequestItems(
            Map("sometable"->List(
              expectedDeleteRequest
            ).asJava).asJava)
      )
    }
  }
}
