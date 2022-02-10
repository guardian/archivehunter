import akka.actor.Props
import akka.testkit.TestProbe
import akka.util.Timeout
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfigurationStatic
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable._
import services.DynamoCapacityActor
import services.DynamoCapacityActor._
import software.amazon.awssdk.services.dynamodb.model.{DescribeTableRequest, DescribeTableResponse, GlobalSecondaryIndexDescription, GlobalSecondaryIndexUpdate, ProvisionedThroughput, ProvisionedThroughputDescription, TableDescription, UpdateGlobalSecondaryIndexAction, UpdateTableRequest, UpdateTableResponse}
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbClient}
import org.mockito.ArgumentMatchers._

import scala.jdk.CollectionConverters._
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
      val mockClient = mock[DynamoDbClient]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): DynamoDbClient = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = TableDescription.builder().tableName("testTable").tableStatus("ACTIVE").build()
      mockClient.describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest]) returns DescribeTableResponse.builder().table(mockedDescription).build()

      toTest ! DynamoCapacityActor.TestAddRequest(testRq)
      toTest ! TimedStateCheck
      testProbe.expectMsg(2 seconds,testProbeMsg)
      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq()
    }

    "not ping the requested actor if a table in the list has a non-active status, and not remove it from the list" in new AkkaTestkitSpecs2Support{
      val mockClient = mock[DynamoDbClient]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): DynamoDbClient = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = TableDescription.builder().tableName("testTable").tableStatus("UPDATING").build()
      mockClient.describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest]) returns DescribeTableResponse.builder().table(mockedDescription).build()

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
      val mockClient = mock[DynamoDbClient]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): DynamoDbClient = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = TableDescription.builder().tableName("testTable").tableStatus("UPDATING").build()
      mockClient.describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest]) returns DescribeTableResponse.builder().table(mockedDescription).build()

      implicit val timeout:Timeout = 10.seconds
      val result = Await.result(toTest ? testRq, 10.seconds).asInstanceOf[AnyRef]
      result must beAnInstanceOf[TableWrongStateError]
    }

    "send an update request for the table and store it for polling" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[DynamoDbClient]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): DynamoDbClient = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = TableDescription.builder()
        .tableName("testTable")
        .tableStatus("ACTIVE")
        .provisionedThroughput(ProvisionedThroughputDescription.builder()
          .readCapacityUnits(6L)
          .writeCapacityUnits(6L)
          .build()
        )
        .build()

      mockClient.describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest]) returns DescribeTableResponse.builder().table(mockedDescription).build()
      mockClient.updateTable(org.mockito.ArgumentMatchers.any[UpdateTableRequest]) returns UpdateTableResponse.builder().tableDescription(mockedDescription).build()

      val expectedRequest = UpdateTableRequest.builder()
        .tableName("testTable")
        .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(12L).writeCapacityUnits(12L).build())
        .build()

      toTest ! testRq
      Thread.sleep(2000L) //give it 2s to process
      there was one(mockClient).describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest])
      there was one(mockClient).updateTable(expectedRequest)

      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq(testRq)

    }

    "immediately pass the message if provisioned capacity matches request" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[DynamoDbClient]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): DynamoDbClient = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(6),Some(6),Seq(), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = TableDescription.builder()
        .tableName("testTable")
        .tableStatus("ACTIVE")
        .provisionedThroughput(ProvisionedThroughputDescription.builder()
          .readCapacityUnits(6L)
          .writeCapacityUnits(6L)
          .build()
        )
        .build()

      mockClient.describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest]) returns DescribeTableResponse.builder().table(mockedDescription).build()
      mockClient.updateTable(org.mockito.ArgumentMatchers.any[UpdateTableRequest]) returns UpdateTableResponse.builder().tableDescription(mockedDescription).build()

      toTest ! testRq
      testProbe.expectMsg(10 seconds, testProbeMsg)
      Thread.sleep(2000L) //give it 2s to process
      there was one(mockClient).describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest])
      there was no(mockClient).updateTable(org.mockito.ArgumentMatchers.any[UpdateTableRequest])

      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq()
    }

    "send an update request for the table and indices" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[DynamoDbClient]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): DynamoDbClient = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testIndexRq = DynamoCapacityActor.UpdateCapacityIndex("testIndex",Some(6),Some(6))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(testIndexRq), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = TableDescription.builder()
        .tableName("testTable")
        .tableStatus("ACTIVE")
        .provisionedThroughput(ProvisionedThroughputDescription.builder()
          .readCapacityUnits(6L)
          .writeCapacityUnits(6L)
          .build()
        )
        .globalSecondaryIndexes(Seq(
          GlobalSecondaryIndexDescription.builder()
            .indexName("testIndex")
            .provisionedThroughput(ProvisionedThroughputDescription.builder()
              .readCapacityUnits(12L)
              .writeCapacityUnits(12L)
              .build()
            )
            .build()
        ).asJavaCollection)
        .build()

      mockClient.describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest]) returns DescribeTableResponse.builder().table(mockedDescription).build()
      mockClient.updateTable(org.mockito.ArgumentMatchers.any[UpdateTableRequest]) returns UpdateTableResponse.builder().tableDescription(mockedDescription).build()

      val expectedIndexUpdates:Seq[GlobalSecondaryIndexUpdate] = Seq(
        GlobalSecondaryIndexUpdate.builder().update(
          UpdateGlobalSecondaryIndexAction.builder()
            .indexName("testIndex")
            .provisionedThroughput(ProvisionedThroughput.builder()
              .readCapacityUnits(6L)
              .writeCapacityUnits(6L)
              .build()
            )
            .build()
        ).build()
      )

      val expectedRequest = UpdateTableRequest.builder()
        .tableName("testTable")
        .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(12L).writeCapacityUnits(12L).build())
        .globalSecondaryIndexUpdates(expectedIndexUpdates.asJavaCollection)
        .build()

      toTest ! testRq
      Thread.sleep(2000L) //give it 2s to process
      there was one(mockClient).describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest])
      there was one(mockClient).updateTable(expectedRequest)

      implicit val timeout:Timeout = 2 seconds
      val checkListResponse = Await.result(toTest ? DynamoCapacityActor.TestGetCheckList, 2 seconds).asInstanceOf[DynamoCapacityActor.TestCheckListResponse]
      checkListResponse.entries mustEqual Seq(testRq)
    }

    "error if the index name does not exist" in new AkkaTestkitSpecs2Support {
      val mockClient = mock[DynamoDbClient]
      val config = new ArchiveHunterConfigurationStatic(Map("externalData.awsRegion"->"fakeregion","externalData.ddbHost"->"fakehost"))
      val fakeDynamoClientMgr = new DynamoClientManager(config) {
        override def getClient(profileName: Option[String]): DynamoDbClient = mockClient
      }

      val testProbe = TestProbe()
      val toTest = system.actorOf(Props(new DynamoCapacityActor(fakeDynamoClientMgr, config)))
      val testIndexRq = DynamoCapacityActor.UpdateCapacityIndex("testWrongIndex",Some(6),Some(6))
      val testRq = DynamoCapacityActor.UpdateCapacityTable("testTable",Some(12),Some(12),Seq(testIndexRq), testProbe.ref, testProbeMsg)

      val mockedDescription:TableDescription = TableDescription.builder()
        .tableName("testTable")
        .tableStatus("ACTIVE")
        .provisionedThroughput(ProvisionedThroughputDescription.builder()
          .readCapacityUnits(6L)
          .writeCapacityUnits(6L)
          .build()
        )
        .globalSecondaryIndexes(Seq(
          GlobalSecondaryIndexDescription.builder()
            .indexName("testIndex")
            .provisionedThroughput(ProvisionedThroughputDescription.builder()
              .readCapacityUnits(12L)
              .writeCapacityUnits(12L)
              .build()
            )
            .build()
        ).asJavaCollection)
        .build()

      mockClient.describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest]) returns DescribeTableResponse.builder()
        .table(mockedDescription)
        .build()

      mockClient.updateTable(org.mockito.ArgumentMatchers.any[UpdateTableRequest]) returns UpdateTableResponse.builder()
        .tableDescription(mockedDescription)
        .build()

      val expectedIndexUpdates:Seq[GlobalSecondaryIndexUpdate] = Seq(
        GlobalSecondaryIndexUpdate.builder().update(UpdateGlobalSecondaryIndexAction.builder()
          .indexName("testIndex")
          .provisionedThroughput(ProvisionedThroughput.builder()
            .readCapacityUnits(6L)
            .writeCapacityUnits(6L)
            .build()
          ).build()
        ).build()
      )

      val expectedRequest = UpdateTableRequest.builder()
        .tableName("testTable")
        .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(12L).writeCapacityUnits(12L).build())
        .globalSecondaryIndexUpdates(expectedIndexUpdates.asJavaCollection)
        .build()

      implicit val timeout:Timeout = 5 seconds

      val result = Await.result(toTest ? testRq, 10.seconds).asInstanceOf[AnyRef]

      there was one(mockClient).describeTable(org.mockito.ArgumentMatchers.any[DescribeTableRequest])
      there was no(mockClient).updateTable(expectedRequest)
      result must beAnInstanceOf[InvalidRequestError]
    }
  }
}
