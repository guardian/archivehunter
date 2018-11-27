import akka.actor.Props
import akka.testkit.TestProbe
import akka.util.Timeout
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model._
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfigurationStatic
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable._
import services.DynamoCapacityActor
import services.DynamoCapacityActor._

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

class DynamoCapacityActorSpec extends Specification with Mockito {
  sequential

  case object testProbeMsg {
    def apply: Any = ()
  }

  "DynamoCapacityActor!TimedStateCheck" should {
    import akka.pattern.ask

    "ping the requested actor if a table in the list has an ACTIVE status, and remove it from the list" in new AkkaTestkitSpecs2Support{
      val mockClient = mock[AmazonDynamoDBAsync]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = new TableDescription().withTableName("testTable").withTableStatus("ACTIVE")
      mockClient.describeTable("testTable") returns new DescribeTableResult().withTable(mockedDescription)

      toTest ! DynamoCapacityActor.TestAddRequest(testRq)
      toTest ! TimedStateCheck
      testProbe.expectMsg(2 seconds,testProbeMsg)
      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq()
    }

    "not ping the requested actor if a table in the list has a non-active status, and not remove it from the list" in new AkkaTestkitSpecs2Support{
      val mockClient = mock[AmazonDynamoDBAsync]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = new TableDescription().withTableName("testTable").withTableStatus("UPDATING")
      mockClient.describeTable("testTable") returns new DescribeTableResult().withTable(mockedDescription)

      toTest ! DynamoCapacityActor.TestAddRequest(testRq)
      toTest ! TimedStateCheck
      testProbe.expectNoMessage(5 seconds)

      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq(testRq)
    }
  }

  "DynamoCapacityActor!UpdateCapacityTable" should {
    import akka.pattern.ask
    "indicate an error if table state is not ACTIVE" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[AmazonDynamoDBAsync]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = new TableDescription().withTableName("testTable").withTableStatus("UPDATING")
      mockClient.describeTable("testTable") returns new DescribeTableResult().withTable(mockedDescription)

      implicit val timeout:Timeout = 10.seconds
      val result = Await.result(toTest ? testRq, 10.seconds).asInstanceOf[AnyRef]
      result must beAnInstanceOf[TableWrongStateError]
    }

    "send an update request for the table and store it for polling" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[AmazonDynamoDBAsync]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = new TableDescription()
        .withTableName("testTable")
        .withTableStatus("ACTIVE")
        .withProvisionedThroughput(new ProvisionedThroughputDescription()
          .withReadCapacityUnits(6L)
          .withWriteCapacityUnits(6L)
        )
      mockClient.describeTable("testTable") returns new DescribeTableResult().withTable(mockedDescription)
      mockClient.updateTable(any[UpdateTableRequest]) returns new UpdateTableResult().withTableDescription(mockedDescription)

      val expectedIndexUpdates:Seq[GlobalSecondaryIndexUpdate] = Seq()

      val expectedRequest = new UpdateTableRequest()
        .withTableName("testTable")
        .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(12L).withWriteCapacityUnits(12L))
        .withGlobalSecondaryIndexUpdates(expectedIndexUpdates.asJavaCollection)

      toTest ! testRq
      Thread.sleep(2000L) //give it 2s to process
      there was one(mockClient).describeTable("testTable")
      there was one(mockClient).updateTable(expectedRequest)

      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq(testRq)

    }

    "immediately pass the message if provisioned capacity matches request" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[AmazonDynamoDBAsync]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(6),Some(6),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = new TableDescription()
        .withTableName("testTable")
        .withTableStatus("ACTIVE")
        .withProvisionedThroughput(new ProvisionedThroughputDescription()
          .withReadCapacityUnits(6L)
          .withWriteCapacityUnits(6L)
        )
      mockClient.describeTable("testTable") returns new DescribeTableResult().withTable(mockedDescription)
      mockClient.updateTable(any[UpdateTableRequest]) returns new UpdateTableResult().withTableDescription(mockedDescription)

      toTest ! testRq
      testProbe.expectMsg(10 seconds, testProbeMsg)
      Thread.sleep(2000L) //give it 2s to process
      there was one(mockClient).describeTable("testTable")
      there was no(mockClient).updateTable(any)

      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq()
    }

    "send an update request for the table and indices" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[AmazonDynamoDBAsync]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testIndexRq = DynamoCapacityActor.UpdateCapacityIndex("testIndex",Some(6),Some(6))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(testIndexRq), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = new TableDescription()
        .withTableName("testTable")
        .withTableStatus("ACTIVE")
        .withProvisionedThroughput(new ProvisionedThroughputDescription()
          .withReadCapacityUnits(6L)
          .withWriteCapacityUnits(6L)
        )
        .withGlobalSecondaryIndexes(Seq(
          new GlobalSecondaryIndexDescription()
            .withIndexName("testIndex")
            .withProvisionedThroughput(new ProvisionedThroughputDescription()
              .withReadCapacityUnits(12L)
              .withWriteCapacityUnits(12L))
        ).asJavaCollection)
      mockClient.describeTable("testTable") returns new DescribeTableResult().withTable(mockedDescription)
      mockClient.updateTable(any[UpdateTableRequest]) returns new UpdateTableResult().withTableDescription(mockedDescription)

      val expectedIndexUpdates:Seq[GlobalSecondaryIndexUpdate] = Seq(
        new GlobalSecondaryIndexUpdate().withUpdate(new UpdateGlobalSecondaryIndexAction().withIndexName("testIndex")
        .withProvisionedThroughput(new ProvisionedThroughput()
          .withReadCapacityUnits(6L)
          .withWriteCapacityUnits(6L)
        )
      ))

      val expectedRequest = new UpdateTableRequest()
        .withTableName("testTable")
        .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(12L).withWriteCapacityUnits(12L))
        .withGlobalSecondaryIndexUpdates(expectedIndexUpdates.asJavaCollection)

      toTest ! testRq
      Thread.sleep(2000L) //give it 2s to process
      there was one(mockClient).describeTable("testTable")
      there was one(mockClient).updateTable(expectedRequest)

      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq(testRq)
    }

    "error if the index name does not exist" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[AmazonDynamoDBAsync]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testIndexRq = DynamoCapacityActor.UpdateCapacityIndex("testWrongIndex",Some(6),Some(6))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(testIndexRq), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = new TableDescription()
        .withTableName("testTable")
        .withTableStatus("ACTIVE")
        .withProvisionedThroughput(new ProvisionedThroughputDescription()
          .withReadCapacityUnits(6L)
          .withWriteCapacityUnits(6L)
        )
        .withGlobalSecondaryIndexes(Seq(
          new GlobalSecondaryIndexDescription()
            .withIndexName("testIndex")
            .withProvisionedThroughput(new ProvisionedThroughputDescription()
              .withReadCapacityUnits(12L)
              .withWriteCapacityUnits(12L))
        ).asJavaCollection)
      mockClient.describeTable("testTable") returns new DescribeTableResult().withTable(mockedDescription)
      mockClient.updateTable(any[UpdateTableRequest]) returns new UpdateTableResult().withTableDescription(mockedDescription)

      val expectedIndexUpdates:Seq[GlobalSecondaryIndexUpdate] = Seq(
        new GlobalSecondaryIndexUpdate().withUpdate(new UpdateGlobalSecondaryIndexAction().withIndexName("testIndex")
          .withProvisionedThroughput(new ProvisionedThroughput()
            .withReadCapacityUnits(6L)
            .withWriteCapacityUnits(6L)
          )
        ))

      val expectedRequest = new UpdateTableRequest()
        .withTableName("testTable")
        .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(12L).withWriteCapacityUnits(12L))
        .withGlobalSecondaryIndexUpdates(expectedIndexUpdates.asJavaCollection)

      implicit val timeout:Timeout = 5 seconds

      val result = Await.result(toTest ? testRq, 10.seconds).asInstanceOf[AnyRef]

      there was one(mockClient).describeTable("testTable")
      there was no(mockClient).updateTable(expectedRequest)
      result must beAnInstanceOf[InvalidRequestError]
    }
  }
}
