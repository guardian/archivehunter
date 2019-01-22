import akka.actor.Props
import akka.testkit.TestProbe
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{DeleteMessageResult, ReceiveMessageRequest}
import com.gu.scanamo.error.{DynamoReadError, NoPropertyOfType}
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import models.JobReportNew
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.ProxyFrameworkQueue

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class ProxyFrameworkQueueSpec extends Specification with Mockito{
  sequential

  "ProxyFrameworkQueue!HandleSuccessfulProxy" should {
    "call updateProxyRef, then update the database and return success" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id","PROXY",None,None,JobStatus.ST_PENDING,None,"fake-source",None,SourceType.SRC_MEDIA)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)

      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function3[String,ArchiveEntry,ProxyType.Value,Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any,any,any) returns Future(Right(None))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew("success",None,"fake-job-id",Some("input-uri"),Some("output-uri"),None)

      val fakeMessage = ProxyFrameworkQueue.HandleSuccessfulProxy(fakeIncoming, mockedJd, mock[ReceiveMessageRequest], "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue"->"someQueue","externalData.indexName"->"someIndex")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      )(mock[ProxyLocationDAO]) {
        override val sqsClient = mockedSqsClient
        override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = Future(Right(mockedArchiveEntry))

        override def updateProxyRef(proxyUri: String, archiveEntry: ArchiveEntry, proxyType: ProxyType.Value): Future[Either[String, Option[ProxyLocation]]] = mockedUpdateProxyRef(proxyUri, archiveEntry, proxyType)
      }))

      toTest ! fakeMessage

      testProbe.expectMsg(10 seconds, akka.actor.Status.Success())
      there was one(mockedUpdateProxyRef).apply("output-uri",mockedArchiveEntry, ProxyType.VIDEO)
      there was one(mockedJobModelDAO).putJob(any)
      there was one(mockedSqsClient).deleteMessage(any)
    }

    "return failure and not delete message if media lookup fails" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id","PROXY",None,None,JobStatus.ST_PENDING,None,"fake-source",None,SourceType.SRC_MEDIA)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)

      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function2[String,ArchiveEntry,Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any,any) returns Future(Right(None))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew("success",None,"fake-job-id",Some("input-uri"),Some("output-uri"),None)

      val fakeMessage = ProxyFrameworkQueue.HandleSuccessfulProxy(fakeIncoming, mockedJd, mock[ReceiveMessageRequest], "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue"->"someQueue","externalData.indexName"->"someIndex")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      )(mock[ProxyLocationDAO]) {
        override val sqsClient = mockedSqsClient
        override def thumbnailJobOriginalMedia(jobDesc: JobModel): Future[Either[String, ArchiveEntry]] = Future(Left("So there"))

      }))

      toTest ! fakeMessage


      testProbe.expectMsgType[akka.actor.Status.Failure](10 seconds)
      there was no(mockedUpdateProxyRef).apply(any,any)
      there was one(mockedJobModelDAO).putJob(any)
      there was no(mockedSqsClient).deleteMessage(any)
    }
  }

  "ProxyFrameworkQueue!HandleRunning" should {
    "update the database record and delete the SQS message" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id","PROXY",None,None,JobStatus.ST_PENDING,None,"fake-source",None,SourceType.SRC_MEDIA)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)
      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function2[String,ArchiveEntry,Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any,any) returns Future(Right(None))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew("running",None,"fake-job-id",Some("input-uri"),None,None)

      val fakeMessage = ProxyFrameworkQueue.HandleRunning(fakeIncoming, mockedJd, mock[ReceiveMessageRequest], "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue"->"someQueue","externalData.indexName"->"someIndex")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      )(mock[ProxyLocationDAO]){
        override protected val sqsClient = mockedSqsClient
      }
      ))

      toTest ! fakeMessage

      testProbe.expectMsg(10 seconds, akka.actor.Status.Success())
      there was one(mockedJobModelDAO).putJob(any)
      there was one(mockedSqsClient).deleteMessage(any)
    }

    "not delete the SQS message if database write fails" in new AkkaTestkitSpecs2Support {
      implicit val ec: ExecutionContext = system.dispatcher
      val mockedJd = new JobModel("fake-job-id","PROXY",None,None,JobStatus.ST_PENDING,None,"fake-source",None,SourceType.SRC_MEDIA)

      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(Some(Left(NoPropertyOfType("something",new AttributeValue()))))
      val mockedScanTargetDAO = mock[ScanTargetDAO]

      val mockedArchiveEntry = mock[ArchiveEntry]
      val mockedUpdateProxyRef = mock[Function2[String,ArchiveEntry,Future[Either[String, Option[ProxyLocation]]]]]
      mockedUpdateProxyRef.apply(any,any) returns Future(Right(None))

      val testProbe = TestProbe()
      val fakeIncoming = JobReportNew("running",None,"fake-job-id",Some("input-uri"),None,None)

      val fakeMessage = ProxyFrameworkQueue.HandleRunning(fakeIncoming, mockedJd, mock[ReceiveMessageRequest], "receipt-handle", testProbe.ref)

      val mockedSqsClient = mock[AmazonSQS]
      mockedSqsClient.deleteMessage(any) returns new DeleteMessageResult()

      val toTest = system.actorOf(Props(new ProxyFrameworkQueue(
        Configuration.from(Map("proxyFramework.notificationsQueue"->"someQueue","externalData.indexName"->"someIndex")),
        system,
        mock[SQSClientManager],
        mock[S3ClientManager],
        mock[DynamoClientManager],
        mockedJobModelDAO,
        mockedScanTargetDAO,
        mock[ESClientManager]
      )(mock[ProxyLocationDAO]){
        override protected val sqsClient = mockedSqsClient
      }
      ))

      toTest ! fakeMessage

      testProbe.expectMsgType[akka.actor.Status.Failure](10 seconds)
      there was one(mockedJobModelDAO).putJob(any)
      there was no(mockedSqsClient).deleteMessage(any)
    }
  }

//  "ProxyFrameworkQueue!HandleFailure" should {
//
//  }
}
