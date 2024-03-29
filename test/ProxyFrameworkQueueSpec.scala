import java.time.ZonedDateTime
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, DeleteMessageResult, ReceiveMessageRequest}
import com.sksamuel.elastic4s.http.update.UpdateResponse
import com.sksamuel.elastic4s.http.{ElasticClient, ElasticError, HttpClient, RequestFailure, RequestSuccess, Shards}
import com.theguardian.multimedia.archivehunter.common.{ProxyType, _}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import models.{JobReportNew, JobReportStatus}
import org.scanamo.{DynamoValue, NoPropertyOfType}
import org.specs2.mock.Mockito
import org.specs2.mock.mockito.MockitoMatchers
import org.specs2.mutable.Specification
import play.api.{Configuration, Logger}
import services.ProxyFrameworkQueue.{HandleRunning, UpdateProblemsIndexSuccess}
import services.{GenericSqsActor, ProxyFrameworkQueue, ProxyFrameworkQueueFunctions}
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Success

class ProxyFrameworkQueueSpec extends Specification with Mockito with MockitoMatchers {
  sequential

  "ProxyFrameworkQueue!HandleSuccessfulProxy" should {
    "call updateProxyRef, then update the database/index and return success" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)

      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function3[String, ArchiveEntry, ProxyType.Value, Future[Either[String, ProxyLocation]]]]
      mockedUpdateProxyRef.apply(any, any, any) returns Future(Right(mock[ProxyLocation]))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew(JobReportStatus.SUCCESS, None, "fake-job-id", Some("input-uri"), Some("output-uri"), None, None, None)
      val fakeMessage = ProxyFrameworkQueue.HandleSuccessfulProxy(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val mockedEntry = mock[ArchiveEntry]
      val mockedUpdatedEntry = mock[ArchiveEntry]
      mockedEntry.copy(any,any,any,any,any,any,any,any,any,any,any,any,any,any,any,any) returns mockedUpdatedEntry

      val mockedIndexer = mock[Indexer]
      mockedIndexer.getById(any)(any) returns Future(mockedEntry)
      mockedIndexer.indexSingleItem(any,any)(any) returns Future(Right("fake-id"))

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      ) {
        override val sqsClient = mockedSqsClient

        override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = Future(Right(mockedArchiveEntry))

        override def updateProxyRef(proxyUri: String, archiveEntry: ArchiveEntry, proxyType: ProxyType.Value): Future[Either[String, ProxyLocation]] = mockedUpdateProxyRef(proxyUri, archiveEntry, proxyType)

        override protected implicit val indexer = mockedIndexer
      }))

      toTest ! fakeMessage

      testProbe.expectMsg(10 seconds, akka.actor.Status.Success)
      there was one(mockedUpdateProxyRef).apply("output-uri", mockedArchiveEntry, ProxyType.VIDEO)
      there was one(mockedIndexer).indexSingleItem(any,any)(any)
      there was one(mockedJobModelDAO).putJob(any)
      there was one(mockedSqsClient).deleteMessage(any)
    }

    "return failure and not delete message if media lookup fails" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)

      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function2[String, ArchiveEntry, Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any, any) returns Future(Right(None))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew(JobReportStatus.SUCCESS, None, "fake-job-id", Some("input-uri"), Some("output-uri"), None, None, None)

      val fakeMessage = ProxyFrameworkQueue.HandleSuccessfulProxy(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val mockedEntry = mock[ArchiveEntry]
      val mockedUpdatedEntry = mock[ArchiveEntry]
      mockedEntry.copy(any,any,any,any,any,any,any,any,any,any,any,any,any,any,any,any) returns mockedUpdatedEntry
      val mockedIndexer = mock[Indexer]
      mockedIndexer.getById(any)(any) returns Future(mockedEntry)
      mockedIndexer.indexSingleItem(any,any)(any) returns Future(Right("fake-id"))

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      ){
        override val sqsClient = mockedSqsClient

        override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = Future(Left("So there"))

        override protected implicit val indexer = mockedIndexer
      }))

      toTest ! fakeMessage

      testProbe.expectMsgType[akka.actor.Status.Failure](10 seconds)
      there was no(mockedUpdateProxyRef).apply(any, any)
      there was one(mockedJobModelDAO).putJob(any)
      there was no(mockedSqsClient).deleteMessage(any)
    }
  }

  "ProxyFrameworkQueue!HandleRunning" should {
    "update the database record and delete the SQS message" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)
      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function2[String, ArchiveEntry, Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any, any) returns Future(Right(None))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew(JobReportStatus.RUNNING, None, "fake-job-id", Some("input-uri"), None, None, None, None)

      val fakeMessage = ProxyFrameworkQueue.HandleRunning(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      ) {
        override protected val sqsClient = mockedSqsClient
      }
      ))

      toTest ! fakeMessage

      testProbe.expectMsg(10 seconds, akka.actor.Status.Success)
      there was one(mockedJobModelDAO).putJob(any)
      there was one(mockedSqsClient).deleteMessage(any)
    }

    "not delete the SQS message if database write fails" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future.failed(new RuntimeException("something blew up"))
      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[(String, ArchiveEntry) => Future[Either[String, Option[ProxyLocation]]]]
      mockedUpdateProxyRef.apply(any, any) returns Future(Right(None))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew(JobReportStatus.RUNNING, None, "fake-job-id", Some("input-uri"), None, None, None, None)

      val fakeMessage = ProxyFrameworkQueue.HandleRunning(fakeIncoming, mockedJd,"some-queue", "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map(
          "proxyFramework.notificationsQueue" -> "someQueue",
          "externalData.indexName" -> "someIndex",
          "externalData.problemItemsIndex" -> "problem-items"
        )),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      ) {
        override protected val sqsClient = mockedSqsClient
      }
      ))

      toTest ! fakeMessage

      testProbe.expectMsgType[akka.actor.Status.Failure](10 seconds)
      there was one(mockedJobModelDAO).putJob(any)
      there was no(mockedSqsClient).deleteMessage(any)
    }
  }

  "ProxyFrameworkQueue!HandleDomainMessage" should {
    "pass on an incoming message if it is not outdated" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, Some(ZonedDateTime.parse("2019-01-02T01:02:03.000Z")))

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.jobForId(any) returns Future(Some(Right(mockedJd)))
      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function2[String, ArchiveEntry, Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any, any) returns Future(Right(None))
      val mockedRq = mock[ReceiveMessageRequest]
      val testProbe = TestProbe()
      val testProbeRef = testProbe.ref

      val fakeIncoming = JobReportNew(JobReportStatus.RUNNING, None, "fake-job-id", Some("input-uri"),
        None, None, None, Some(ZonedDateTime.parse("2019-01-02T01:02:04.000Z")))

      val fakeMessage = GenericSqsActor.HandleDomainMessage(fakeIncoming, "some-queue", "receipt-handle")

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      ) {
        override protected val sqsClient = mockedSqsClient
        override protected val ownRef = testProbeRef
      }
      ))

      toTest ! fakeMessage

      testProbe.expectMsgAllClassOf(HandleRunning(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbeRef).getClass)
      there was no(mockedSqsClient).deleteMessage(any)
    }

    "swallow and delete an incoming message if it is outdated" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, Some(ZonedDateTime.parse("2019-01-02T01:02:04.000Z")))

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.jobForId(any) returns Future(Some(Right(mockedJd)))
      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function2[String, ArchiveEntry, Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any, any) returns Future(Right(None))

      val mockedRq = mock[ReceiveMessageRequest]
      mockedRq.getQueueUrl returns "fake-queue-url"

      val testProbe = TestProbe()
      val testProbeRef = testProbe.ref

      val fakeIncoming = JobReportNew(JobReportStatus.RUNNING, None, "fake-job-id", Some("input-uri"), None, None, None, Some(ZonedDateTime.parse("2019-01-02T01:02:03.000Z")))

      val fakeMessage = GenericSqsActor.HandleDomainMessage(fakeIncoming, "fake-queue-url", "receipt-handle")

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      ) {
        override protected val sqsClient = mockedSqsClient
        override protected val ownRef = testProbeRef
      }
      ))

      toTest ! fakeMessage

      testProbe.expectNoMessage(2.seconds)
      there was one(mockedSqsClient).deleteMessage(new DeleteMessageRequest().withQueueUrl("fake-queue-url").withReceiptHandle("receipt-handle"))
    }
  }

  "ProxyFrameworkQueue.convertMessageBody" should {
    "parse the message body and return a domain object, including the message timestamp" in new AkkaTestkitSpecs2Support {
      val msgContent =
        """{
          |  "Type" : "Notification",
          |  "MessageId" : "1e7c68da-f1f4-5520-b996-3c3be68e5384",
          |  "TopicArn" : "arn:aws:sns:eu-west-1:855023211239:ArchiveHunter-PROD-ProxyFramework-ReplyTopic-FFP28QTZSHLD",
          |  "Message" : "{\n  \"status\" : \"FAILURE\",\n  \"output\" : null,\n  \"jobId\" : \"46daa46f-b979-4459-b13f-45b73ffc1210\",\n  \"input\" : \"\",\n  \"log\" : \"NDAwMCBmNTNlMDFmOS03OWVhLTQ0MzAtODgwMC0yZjhmNDQ0MjgzMzg6IEFtYXpvbiBFbGFzdGljIFRyYW5zY29kZXIgY291bGQgbm90IGludGVycHJldCB0aGUgbWVkaWEgZmlsZS4=\",\n  \"proxyType\" : \"VIDEO\",\n  \"metadata\" : null\n}",
          |  "Timestamp" : "2019-02-15T19:38:34.175Z",
          |  "SignatureVersion" : "1",
          |  "Signature" : "SHHE3Y1sCMrXDwD01ijvAIV8td++/5rdGDtFomkZ3pFKbovLYtrC9NFl6oR2zm6vaeZjf9G0bzo7sI+HmKE2BvRevi8ivKLQiyyN013o37p355291y0Js1jXY46dIIBDFMDgLKX7S2zuAkaZjdreFvgqe3WWaEVZmKDBvEKDkBDZr1U0LTjmyfOi7aPakY7F2D/BorExCy8Zh65/PNnanNzZIvGt94TlB/gGGyYSmBUA7Yomj3AW9GIXlvefWxGvh/9KK9knYqFs9TrPylxBnxLrAIUyY8y2eH18xMmJK/fwaSBtQd1wIx0y1leGbM13Ah1McWU17jzJ6ZKo//Pztg==",
          |  "SigningCertURL" : "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-6aad65c2f9911b05cd53efda11f913f9.pem",
          |  "UnsubscribeURL" : "https://sns.eu-west-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:eu-west-1:855023211239:ArchiveHunter-PROD-ProxyFramework-ReplyTopic-FFP28QTZSHLD:67a2d4a4-fc74-456a-b67d-b6d76aaed065"
          |}
        """.stripMargin

      class TestClass extends ProxyFrameworkQueueFunctions {
        override protected val indexer = mock[Indexer]
        override protected val logger = mock[Logger]
        override protected val esClient = mock[ElasticClient]
      }

      val toTest = new TestClass

      val result = toTest.convertMessageBody(msgContent)
      result must beRight(JobReportNew(JobReportStatus.FAILURE,
        Some("NDAwMCBmNTNlMDFmOS03OWVhLTQ0MzAtODgwMC0yZjhmNDQ0MjgzMzg6IEFtYXpvbiBFbGFzdGljIFRyYW5zY29kZXIgY291bGQgbm90IGludGVycHJldCB0aGUgbWVkaWEgZmlsZS4="),
        "46daa46f-b979-4459-b13f-45b73ffc1210",
        Some(""),
        None,
        Some(ProxyType.VIDEO),
        None,
        Some(ZonedDateTime.parse("2019-02-15T19:38:34.175Z"))))
    }


    "ProxyFrameworkQueue!HandleWarning" should {
      "update the proxy references if there is an output URL" in new AkkaTestkitSpecs2Support {
        implicit val ec: ExecutionContext = system.dispatcher
        val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

        val mockedJobModelDAO = mock[JobModelDAO]
        mockedJobModelDAO.putJob(any) returns Future(None)
        val mockedScanTargetDAO = mock[ScanTargetDAO]

        val mockedArchiveEntry = mock[ArchiveEntry]
        val mockedUpdateProxyRef = mock[Function2[String, ArchiveEntry, Future[Either[String, ProxyLocation]]]]
        mockedUpdateProxyRef.apply(any, any) returns Future(Right(mock[ProxyLocation]))

        val testProbe = TestProbe()
        val fakeIncoming = JobReportNew(JobReportStatus.WARNING, None, "fake-job-id", Some("input-uri"), Some("s3://proxybucket/path/to/file.mp4"), None, None, None)

        val fakeMessage = ProxyFrameworkQueue.HandleWarning(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbe.ref)

        val mockUpdateProxyRef = mock[Function3[String, ArchiveEntry, ProxyType.Value, Future[Either[String, ProxyLocation]]]]
        mockUpdateProxyRef.apply(any, any, any) returns Future(Right(ProxyLocation("xxxfileid", "xxxproxyId", ProxyType.VIDEO, "proxybucket", "/path/to/proxy.mp4", Some("myregion"), StorageClass.STANDARD)))
        val mockedSqsClient = mock[AmazonSQS]
        mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

        val mockThumnailJobOriginalMedia = mock[Function1[JobModel, Future[Either[String, ArchiveEntry]]]]
        mockThumnailJobOriginalMedia.apply(any) returns Future(Right(mockedArchiveEntry))

        implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
        val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
          Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
          system,
          mock[SQSClientManager],
          mock[S3ClientManager],
          mock[DynamoClientManager],
          mockedJobModelDAO,
          mockedScanTargetDAO,
          mock[ESClientManager]
        ) {
          override protected val sqsClient = mockedSqsClient

          override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = mockThumnailJobOriginalMedia(jobDesc)

          override def updateProxyRef(proxyUri: String, archiveEntry: ArchiveEntry, proxyType: ProxyType.Value): Future[Either[String, ProxyLocation]] = mockUpdateProxyRef(proxyUri, archiveEntry, proxyType)
        }
        ))

        toTest ! fakeMessage

        testProbe.expectMsg(10 seconds, akka.actor.Status.Success)
        there was one(mockedJobModelDAO).putJob(any)
        there was one(mockUpdateProxyRef).apply("s3://proxybucket/path/to/file.mp4", mockedArchiveEntry, ProxyType.VIDEO)
        there was one(mockedSqsClient).deleteMessage(any)
      }

      "not try to update anything if there is no URL" in new AkkaTestkitSpecs2Support {
        implicit val ec: ExecutionContext = system.dispatcher
        val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

        val mockedJobModelDAO = mock[JobModelDAO]
        mockedJobModelDAO.putJob(any) returns Future(None)
        val mockedScanTargetDAO = mock[ScanTargetDAO]

        val mockedArchiveEntry = mock[ArchiveEntry]
//        val mockedUpdateProxyRef = mock[Function2[String, ArchiveEntry, Future[Either[String, Option[ProxyLocation]]]]]
//        mockedUpdateProxyRef.apply(any, any) returns Future(Right(None))

        val testProbe = TestProbe()
        val fakeIncoming = JobReportNew(JobReportStatus.WARNING, None, "fake-job-id", Some("input-uri"), None, None, None, None)

        val fakeMessage = ProxyFrameworkQueue.HandleWarning(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbe.ref)

        val mockUpdateProxyRef = mock[(String, ArchiveEntry, ProxyType.Value) => Future[Either[String, ProxyLocation]]]
        mockUpdateProxyRef.apply(any, any, any) returns Future(Right(ProxyLocation("xxxfileid", "xxxproxyId", ProxyType.VIDEO, "proxybucket", "/path/to/proxy.mp4", Some("myregion"), StorageClass.STANDARD)))

        val mockedSqsClient = mock[AmazonSQS]
        mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

        val mockThumnailJobOriginalMedia = mock[Function1[JobModel, Future[Either[String, ArchiveEntry]]]]
        mockThumnailJobOriginalMedia.apply(any) returns Future(Right(mockedArchiveEntry))

        implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
        val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
          Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
          system,
          mock[SQSClientManager],
          mock[S3ClientManager],
          mock[DynamoClientManager],
          mockedJobModelDAO,
          mockedScanTargetDAO,
          mock[ESClientManager]
        ) {
          override protected val sqsClient = mockedSqsClient

          override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = mockThumnailJobOriginalMedia(jobDesc)

          override def updateProxyRef(proxyUri: String, archiveEntry: ArchiveEntry, proxyType: ProxyType.Value): Future[Either[String, ProxyLocation]] = mockUpdateProxyRef(proxyUri, archiveEntry, proxyType)
        }
        ))

        toTest ! fakeMessage

        testProbe.expectMsg(10 seconds, akka.actor.Status.Success)
        there was one(mockedJobModelDAO).putJob(any)
        there was no(mockThumnailJobOriginalMedia).apply(any)
        there was no(mockUpdateProxyRef).apply(any, any, any)
        there was one(mockedSqsClient).deleteMessage(any)
      }
    }
  }

  "ProxyFrameworkQueue!UpdateProblemsIndexSuccess" should {
    "clear the problem marker for a specific proxy type" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)

      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function3[String, ArchiveEntry, ProxyType.Value, Future[Either[String, ProxyLocation]]]]
      mockedUpdateProxyRef.apply(any, any, any) returns Future(Right(mock[ProxyLocation]))

      val problemItemFake = ProblemItem("fake-fileid","fakecollection","/path/to/file", esRecordSays = false, verifyResults=Seq(
        ProxyVerifyResult("fake-fileid",ProxyType.VIDEO, true, false, Some(false)),
        ProxyVerifyResult("fake-fileid",ProxyType.THUMBNAIL, true, false, Some(false)),
      ), decision=None)
      val mockedProblemItemIndexer = mock[ProblemItemIndexer]
      mockedProblemItemIndexer.getById(any)(any) returns Future(problemItemFake)
      mockedProblemItemIndexer.indexSingleItem(any,any,any)(any) returns Future(Success("some-id"))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew(JobReportStatus.SUCCESS, None, "fake-job-id", Some("input-uri"), Some("output-uri"), Some(ProxyType.VIDEO), None, None)
      val fakeMessage = ProxyFrameworkQueue.UpdateProblemsIndexSuccess(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val mockedEntry = mock[ArchiveEntry]
      val mockedUpdatedEntry = mock[ArchiveEntry]
      mockedEntry.copy(any,any,any,any,any,any,any,any,any,any,any,any,any,any,any,any) returns mockedUpdatedEntry
      val mockedIndexer = mock[Indexer]
      mockedIndexer.getById(any)(any) returns Future(mockedEntry)
      mockedIndexer.indexSingleItem(any,any)(any) returns Future(Right("fake-id"))

      val mockedEsClient = mock[com.sksamuel.elastic4s.http.ElasticClient]
      val mockedEsClientManager = mock[ESClientManager]
      mockedEsClientManager.getClient() returns mockedEsClient

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mockedEsClientManager
      ){
        override val problemItemIndexName = "problem-items"

        override val problemItemIndexer = mockedProblemItemIndexer

        override val sqsClient = mockedSqsClient

        override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = Future(Right(mockedArchiveEntry))

        override def updateProxyRef(proxyUri: String, archiveEntry: ArchiveEntry, proxyType: ProxyType.Value): Future[Either[String, ProxyLocation]] = mockedUpdateProxyRef(proxyUri, archiveEntry, proxyType)

        override protected implicit val indexer = mockedIndexer
      }))

      toTest ! fakeMessage
      Thread.sleep(1000)
      //testProbe.expectMsg(10 seconds, akka.actor.Status.Success)

      there was one(mockedProblemItemIndexer).getById("fake-source")(mockedEsClient)
      there was one(mockedProblemItemIndexer).indexSingleItem(any,any,any)(any)
    }

    "not fail if no problem marker is presemt" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id", "PROXY", None, None, JobStatus.ST_PENDING, None, "fake-source", None, SourceType.SRC_MEDIA, None)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)

      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function3[String, ArchiveEntry, ProxyType.Value, Future[Either[String, ProxyLocation]]]]
      mockedUpdateProxyRef.apply(any, any, any) returns Future(Right(mock[ProxyLocation]))

      val problemItemFake = ProblemItem("fake-fileid","fakecollection","/path/to/file", esRecordSays = false, verifyResults=Seq(
        ProxyVerifyResult("fake-fileid",ProxyType.VIDEO, true, false, Some(false)),
        ProxyVerifyResult("fake-fileid",ProxyType.THUMBNAIL, true, false, Some(false)),
      ), decision=None)
      val mockedProblemItemIndexer = mock[ProblemItemIndexer]
      mockedProblemItemIndexer.getById(any)(any) returns Future(problemItemFake)
      mockedProblemItemIndexer.indexSingleItem(any,any,any)(any) returns Future(Success("some-id"))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew(JobReportStatus.SUCCESS, None, "fake-job-id", Some("input-uri"), Some("output-uri"), Some(ProxyType.VIDEO), None, None)
      val fakeMessage = ProxyFrameworkQueue.UpdateProblemsIndexSuccess(fakeIncoming, mockedJd, "some-queue", "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val mockedEntry = mock[ArchiveEntry]
      val mockedUpdatedEntry = mock[ArchiveEntry]
      mockedEntry.copy(any,any,any,any,any,any,any,any,any,any,any,any,any,any,any,any) returns mockedUpdatedEntry
      val mockedIndexer = mock[Indexer]
      mockedIndexer.getById(any)(any) throws new RuntimeException("nothing existed")
      mockedIndexer.indexSingleItem(any,any)(any) returns Future(Right("fake-id"))

      val mockedEsClient = mock[com.sksamuel.elastic4s.http.ElasticClient]
      val mockedEsClientManager = mock[ESClientManager]
      mockedEsClientManager.getClient() returns mockedEsClient

      implicit val fakeProxyLocationDAO = mock[ProxyLocationDAO]
      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue" -> "someQueue", "externalData.indexName" -> "someIndex", "externalData.problemItemsIndex" -> "problem-items")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mockedEsClientManager
      ) {
        override val problemItemIndexName = "problem-items"

        override val problemItemIndexer = mockedProblemItemIndexer

        override val sqsClient = mockedSqsClient

        override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = Future(Right(mockedArchiveEntry))

        override def updateProxyRef(proxyUri: String, archiveEntry: ArchiveEntry, proxyType: ProxyType.Value): Future[Either[String, ProxyLocation]] = mockedUpdateProxyRef(proxyUri, archiveEntry, proxyType)

        override protected implicit val indexer = mockedIndexer
      }))

      toTest ! fakeMessage
      Thread.sleep(1000)

      there was one(mockedProblemItemIndexer).getById("fake-source")(mockedEsClient)
      there was one(mockedProblemItemIndexer).indexSingleItem(any,any,any)(any)
    }
  }
}
