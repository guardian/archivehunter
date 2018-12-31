import java.time.ZonedDateTime
import akka.actor.Props
import akka.testkit.TestProbe
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IngestMessage, ScanTargetDAO}
import com.theguardian.multimedia.archivehunter.common.cmn_services.ProxyGenerators
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.IngestProxyQueue
import akka.pattern.ask
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBAsync}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model._
import io.circe.syntax._
import io.circe.generic.auto._
import models.AwsSqsMsg

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class IngestProxyQueueSpec extends Specification with Mockito with ZonedDateTimeEncoder with StorageClassEncoder {
  sequential

  "IngestProxyQueue!CheckRegisteredThumb" should {
    "request location from proxyLocationDAO and return Success with no further action if a thumb exists" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]
      mockProxyLocationDAO.getProxy(anyString,any)(any) returns Future(Some(mockProxyLocation))

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CheckRegisteredThumb(mockEntry), 30 seconds)
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.THUMBNAIL)(mockDDBClient)

      result mustEqual akka.actor.Status.Success
    }

    "request location from proxyLocationDAO and dispatch CheckNonRegisteredThumb if no thumb exists" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]
      mockProxyLocationDAO.getProxy(anyString,any)(any) returns Future(None)

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      toTest ! IngestProxyQueue.CheckRegisteredThumb(mockEntry)

      mockedSelf.expectMsg(IngestProxyQueue.CheckNonRegisteredThumb(mockEntry))
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.THUMBNAIL)(mockDDBClient)
    }

    "signal a failure if the proxyLocationDAO lookup fails" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]
      mockProxyLocationDAO.getProxy(anyString,any)(any) returns Future.failed(new RuntimeException("my hovercraft is full of eels"))

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CheckRegisteredThumb(mockEntry), 30 seconds)
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.THUMBNAIL)(mockDDBClient)

      result mustEqual akka.actor.Status.Failure
    }
  }

  //can't test CheckNonRegisteredThumb at present since it relies on a static object method

  "IngestProxyQueue!CreateNewThumbnail" should {
    "call out to ProxyGenerators to initiate job and return success" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      mockProxyGenerators.createThumbnailProxy(any[ArchiveEntry]) returns Future(Success("fake-job-id"))
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CreateNewThumbnail(mockEntry), 30 seconds)
      there was one(mockProxyGenerators).createThumbnailProxy(mockEntry)

      result mustEqual akka.actor.Status.Success
    }

    "return failure if the job does not trigger" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      mockProxyGenerators.createThumbnailProxy(any[ArchiveEntry]) returns Future(Failure(new RuntimeException("boo!")))
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CreateNewThumbnail(mockEntry), 30 seconds)
      there was one(mockProxyGenerators).createThumbnailProxy(mockEntry)

      result mustEqual akka.actor.Status.Failure
    }

    "return failure if the ProxyGenerators thread crashes" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      mockProxyGenerators.createThumbnailProxy(any[ArchiveEntry]) returns Future.failed(new RuntimeException("my hovercraft is full of eels"))
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CreateNewThumbnail(mockEntry), 30 seconds)
      there was one(mockProxyGenerators).createThumbnailProxy(mockEntry)

      result mustEqual akka.actor.Status.Failure
    }
  }

  //can't test CheckNonRegisteredProxy at the moment because it uses a static object

  "IngestProxyQueue!CheckRegisteredProxy" should {
    "call to ProxyLocationDAO for video and audio proxies and dispatch CheckNonRegisteredProxy if neither is present" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]
      mockProxyLocationDAO.getProxy(anyString,any)(any) returns Future(None)

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      toTest ! IngestProxyQueue.CheckRegisteredProxy(mockEntry)
      mockedSelf.expectMsg(IngestProxyQueue.CheckNonRegisteredProxy(mockEntry))
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.VIDEO)(mockDDBClient)
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.AUDIO)(mockDDBClient)
    }

    "call to ProxyLocationDAO for video and audio proxies and dispatch Success back if either exists" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]
      mockProxyLocationDAO.getProxy(anyString,any)(any) returns Future(None)
      mockProxyLocationDAO.getProxy(anyString,org.mockito.Matchers.eq(ProxyType.VIDEO))(any) returns Future(Some(mockProxyLocation))

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      val response = Await.result(toTest ? IngestProxyQueue.CheckRegisteredProxy(mockEntry), 30 seconds)
      mockedSelf.expectNoMessage(1.seconds) //don't need to delay as Await above has Await means we're guaranteed to run this once
                                            //routine has completed
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.VIDEO)(mockDDBClient)
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.AUDIO)(mockDDBClient)
      response mustEqual akka.actor.Status.Success
    }

    "call to ProxyLocationDAO for video and audio proxies and dispatch Failed back if any future returns a failure" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[AmazonDynamoDBAsync]
      mockDDBlientMgr.getClient(any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]
      mockProxyLocationDAO.getProxy(anyString,any)(any) returns Future(None)
      mockProxyLocationDAO.getProxy(anyString,org.mockito.Matchers.eq(ProxyType.VIDEO))(any) returns Future.failed(new RuntimeException("kaboom"))

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      val response = Await.result(toTest ? IngestProxyQueue.CheckRegisteredProxy(mockEntry), 30 seconds)
      mockedSelf.expectNoMessage(1.seconds) //don't need to delay as Await above has Await means we're guaranteed to run this once
      //routine has completed
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.VIDEO)(mockDDBClient)
      there was one(mockProxyLocationDAO).getProxy("fake-id",ProxyType.AUDIO)(mockDDBClient)
      response mustEqual akka.actor.Status.Failure
    }
  }

  "IngestProxyQueue!HandleNextSqsMessage" should {
    "reply Success if the message list is empty" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSqsClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSqsClient

      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val rq = new ReceiveMessageRequest()
      val msgList:java.util.Collection[Message] = Seq().asJavaCollection

      val msgResponse = new ReceiveMessageResult().withMessages(msgList)
      mockSqsClient.receiveMessage(rq) returns msgResponse
      val response = Await.result(toTest ? IngestProxyQueue.HandleNextSqsMessage(rq), 30 seconds)

      response mustEqual akka.actor.Status.Success
    }

    "dispatch CheckRegisteredThumb and CheckRegisteredProxy for each message, then dispatch HandleNextSqsMessage again" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.empty
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSqsClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSqsClient

      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      //the match fails if you don't use withFixedOffsetZone as somewhere in the marshalling/unmarshalling the zone info is changed
      val fakeEntry1 = ArchiveEntry("fake-id-1","fakebucket","/path/to/file",Some(".ext"),1234L,ZonedDateTime.now().withFixedOffsetZone(),"etag",MimeType("video","mp4"),false,StorageClass.STANDARD_IA,Seq(),false)
      val fakeEntry2 = ArchiveEntry("fake-id-2","fakebucket","/path/to/file2",Some(".ext"),1234L,ZonedDateTime.now().withFixedOffsetZone(),"etag2",MimeType("video","mp4"),false,StorageClass.STANDARD_IA,Seq(),false)

      val rq = new ReceiveMessageRequest()
      val msgList:java.util.Collection[Message] = Seq(
        new Message().withMessageId("fake-message-1")
          .withReceiptHandle("fake1")
          .withMessageAttributes(Map().asInstanceOf[Map[String,MessageAttributeValue]].asJava)
          .withBody(
            AwsSqsMsg(
              "faketype",
              "id-msg-1",
              "fakearn",
              "",
              IngestMessage(fakeEntry1,"fake-id-1").asJson.toString,
              "notime"
            ).asJson.toString),
        new Message().withMessageId("fake-message-2")
          .withReceiptHandle("fake2")
          .withMessageAttributes(Map().asInstanceOf[Map[String,MessageAttributeValue]].asJava)
          .withBody(
            AwsSqsMsg(
              "faketype",
              "id-msg-1",
              "fakearn",
              "",
              IngestMessage(fakeEntry2,"fake-id-2").asJson.toString,
              "notime"
            ).asJson.toString)
      ).asJavaCollection

      val msgResponse = new ReceiveMessageResult().withMessages(msgList)
      mockSqsClient.receiveMessage(rq) returns msgResponse
      mockSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      toTest ! IngestProxyQueue.HandleNextSqsMessage(rq)

      mockedSelf.expectMsg(IngestProxyQueue.CheckRegisteredThumb(fakeEntry1))
      mockedSelf.expectMsg(IngestProxyQueue.CheckRegisteredProxy(fakeEntry1))

      mockedSelf.expectMsg(IngestProxyQueue.CheckRegisteredThumb(fakeEntry2))
      mockedSelf.expectMsg(IngestProxyQueue.CheckRegisteredProxy(fakeEntry2))

      mockedSelf.expectMsg(IngestProxyQueue.HandleNextSqsMessage(rq))

      there was one(mockSqsClient).deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle("fake1"))
      there was one(mockSqsClient).deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle("fake2"))
    }
  }

  "IngestProxyQueue!CheckForNotifications" should {
    "construct an SQS request and dispatch HandleNextSqsMessage" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"testQueueUrl"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSqsClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSqsClient

      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      toTest ! IngestProxyQueue.CheckForNotifications

      mockedSelf.expectMsg(IngestProxyQueue.HandleNextSqsMessage(new ReceiveMessageRequest().withQueueUrl("testQueueUrl")
        .withWaitTimeSeconds(10)
        .withMaxNumberOfMessages(10)))
    }

    "dispatch failure if the queue name is set to default value" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"queueUrl"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSqsClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSqsClient

      val mockProxyGenerators = mock[ProxyGenerators]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockProxyLocationDAO, mockS3ClientMgr,
        mockDDBlientMgr, mockEtsProxyActor.ref) {
        override protected val ipqActor = mockedSelf.ref
      }))

      val result = Await.result(toTest ? IngestProxyQueue.CheckForNotifications, 10 seconds)
      result mustEqual akka.actor.Status.Failure
      mockedSelf.expectNoMessage()
    }
  }
}
