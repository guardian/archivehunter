import akka.actor.Props
import akka.testkit.TestProbe
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation, ProxyLocationDAO, ProxyType}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTargetDAO
import com.theguardian.multimedia.archivehunter.common.cmn_services.ProxyGenerators
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.IngestProxyQueue
import akka.pattern.ask
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBAsync}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class IngestProxyQueueSpec extends Specification with Mockito {
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


}
