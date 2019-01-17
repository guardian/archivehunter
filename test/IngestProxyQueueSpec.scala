import java.time.ZonedDateTime

import akka.actor.Props
import akka.testkit.TestProbe
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IngestMessage, ScanTargetDAO}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.{GenericSqsActor, IngestProxyQueue}
import akka.pattern.ask
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBAsync}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model._
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.{ProxyGenerators, RequestType}
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
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
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
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
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
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
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
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      mockProxyGenerators.requestProxyJob(any,any[ArchiveEntry],any) returns Future(Success("fake-job-id"))
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CreateNewThumbnail(mockEntry), 30 seconds)
      there was one(mockProxyGenerators).requestProxyJob(RequestType.THUMBNAIL,mockEntry,None)

      result mustEqual akka.actor.Status.Success
    }

    "return failure if the job does not trigger" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      mockProxyGenerators.requestProxyJob(any,any[ArchiveEntry],any) returns Future(Failure(new RuntimeException("boo!")))
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CreateNewThumbnail(mockEntry), 30 seconds)
      there was one(mockProxyGenerators).requestProxyJob(RequestType.THUMBNAIL,mockEntry,None)

      result mustEqual akka.actor.Status.Failure
    }

    "return failure if the ProxyGenerators thread crashes" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockProxyGenerators = mock[ProxyGenerators]
      mockProxyGenerators.requestProxyJob(any,any[ArchiveEntry],any) returns Future.failed(new RuntimeException("my hovercraft is full of eels"))
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
      }))

      val mockProxyLocation = mock[ProxyLocation]

      val mockEntry = mock[ArchiveEntry]
      mockEntry.id returns "fake-id"

      mockedSelf.expectNoMessage(2 seconds)
      val result = Await.result(toTest ? IngestProxyQueue.CreateNewThumbnail(mockEntry), 30 seconds)
      there was one(mockProxyGenerators).requestProxyJob(RequestType.THUMBNAIL, mockEntry,None)

      result mustEqual akka.actor.Status.Failure
    }
  }

  //can't test CheckNonRegisteredProxy at the moment because it uses a static object

  "IngestProxyQueue!CheckRegisteredProxy" should {
    "call to ProxyLocationDAO for video and audio proxies and dispatch CheckNonRegisteredProxy if neither is present" in new AkkaTestkitSpecs2Support {
      implicit val ec=system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
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
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
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
      val testConfig = Configuration.from(Map("ingest.notificationsQueue"->"someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockDDBClient = mock[DynamoClient]
      mockDDBlientMgr.getNewAlpakkaDynamoClient(any)(any, any) returns mockDDBClient
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
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
      implicit val ec = system.dispatcher
      implicit val timeout: akka.util.Timeout = 30 seconds
      val testConfig = Configuration.from(Map("ingest.notificationsQueue" -> "someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSqsClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSqsClient

      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
      }))

      val rq = new ReceiveMessageRequest()
      val msgList: java.util.Collection[Message] = Seq().asJavaCollection

      val msgResponse = new ReceiveMessageResult().withMessages(msgList)
      mockSqsClient.receiveMessage(rq) returns msgResponse
      val response = Await.result(toTest ? GenericSqsActor.HandleNextSqsMessage(rq), 30 seconds)

      response mustEqual akka.actor.Status.Success
    }
  }

  "IngestProxyQueue!HandleDomainMessage" should {
    "dispatch CheckRegisteredThumb and CheckRegisteredProxy for each message, then dispatch HandleNextSqsMessage again" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher
      implicit val timeout: akka.util.Timeout = 30 seconds
      val testConfig = Configuration.from(Map("ingest.notificationsQueue" -> "someQueue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSqsClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSqsClient

      val mockProxyGenerators = mock[ProxyGenerators]
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
      }))

      //the match fails if you don't use withFixedOffsetZone as somewhere in the marshalling/unmarshalling the zone info is changed
      val fakeEntry1 = ArchiveEntry("fake-id-1", "fakebucket", "/path/to/file", Some(".ext"), 1234L, ZonedDateTime.now().withFixedOffsetZone(), "etag", MimeType("video", "mp4"), false, StorageClass.STANDARD_IA, Seq(), false)
      val fakeEntry2 = ArchiveEntry("fake-id-2", "fakebucket", "/path/to/file2", Some(".ext"), 1234L, ZonedDateTime.now().withFixedOffsetZone(), "etag2", MimeType("video", "mp4"), false, StorageClass.STANDARD_IA, Seq(), false)

      val rq = new ReceiveMessageRequest()
      val msgList: java.util.Collection[Message] = Seq(
        new Message().withMessageId("fake-message-1")
          .withReceiptHandle("fake1")
          .withMessageAttributes(Map().asInstanceOf[Map[String, MessageAttributeValue]].asJava)
          .withBody(
            IngestMessage(fakeEntry1, "fake-id-1").asJson.toString,
          ),
        new Message().withMessageId("fake-message-2")
          .withReceiptHandle("fake2")
          .withMessageAttributes(Map().asInstanceOf[Map[String, MessageAttributeValue]].asJava)
          .withBody(
            IngestMessage(fakeEntry2, "fake-id-2").asJson.toString
          )
      ).asJavaCollection

      val msgResponse = new ReceiveMessageResult().withMessages(msgList)
      mockSqsClient.receiveMessage(rq) returns msgResponse
      mockSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      toTest ! GenericSqsActor.HandleDomainMessage(IngestMessage(fakeEntry1,"fake-id-1"), rq, "fake1")

      mockedSelf.expectMsg(IngestProxyQueue.CheckRegisteredThumb(fakeEntry1))
      mockedSelf.expectMsg(IngestProxyQueue.CheckRegisteredProxy(fakeEntry1))

      //this message does not delete the SQS message any more; this is only done on successful completion of processing
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
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
      }))

      toTest ! GenericSqsActor.CheckForNotifications

      mockedSelf.expectMsg(GenericSqsActor.HandleNextSqsMessage(new ReceiveMessageRequest().withQueueUrl("testQueueUrl")
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
      implicit val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockS3ClientMgr = mock[S3ClientManager]
      val mockDDBlientMgr = mock[DynamoClientManager]
      val mockEtsProxyActor = TestProbe()
      implicit val mockScanTargetDAO = mock[ScanTargetDAO]
      val mockedSelf = TestProbe()

      val toTest = system.actorOf(Props(new IngestProxyQueue(testConfig, system, mockSqsClientMgr, mockProxyGenerators, mockS3ClientMgr,
        mockDDBlientMgr) {
        override protected val ownRef = mockedSelf.ref
      }))

      val result = Await.result(toTest ? GenericSqsActor.CheckForNotifications, 10 seconds)
      result mustEqual akka.actor.Status.Failure
      mockedSelf.expectNoMessage()
    }
  }
}
