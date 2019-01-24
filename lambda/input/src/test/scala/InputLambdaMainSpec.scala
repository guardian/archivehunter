import java.time.{Instant, ZonedDateTime}
import java.util.Date

import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification._
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common._
import org.specs2.mock.Mockito
import org.specs2.mutable._
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{SendMessageRequest, SendMessageResult}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import io.circe.syntax._
import io.circe.generic.auto._

import collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Success
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class InputLambdaMainSpec extends Specification with Mockito with ZonedDateTimeEncoder with StorageClassEncoder {

  "InputLambdaMain" should {
    "call to index an item delivered via an S3Event then call to dispatch a message to the main app" in {
      val fakeEvent = new S3Event(Seq(
        new S3EventNotificationRecord("aws-fake-region","ObjectCreated:Put","unit_test",
        "2018-01-01T11:12:13.000Z","1",
          new RequestParametersEntity("localhost"),
          new ResponseElementsEntity("none","fake-req-id"),
          new S3Entity("fake-config-id",
            new S3BucketEntity("my-bucket", new UserIdentityEntity("owner"),"arn"),
            new S3ObjectEntity("path/to/object",1234L,"fakeEtag","v1"),"1"),
          new UserIdentityEntity("no-principal"))
      ).asJava)

      val fakeContext = mock[Context]
      val mockIndexer = mock[Indexer]
      val mockSendIngestedMessage = mock[Function1[ArchiveEntry, Unit]]

      mockIndexer.indexSingleItem(any,any,any)(any).returns(Future(Success("fake-entry-id")))
      val test = new InputLambdaMain {
        override protected def getElasticClient(clusterEndpoint: String): HttpClient = {
          val m = mock[HttpClient]

          m
        }

        override def sendIngestedMessage(entry:ArchiveEntry) = mockSendIngestedMessage(entry)

        override protected def getClusterEndpoint = "testClusterEndpoint"

        override protected def getIndexName = "testIndexName"

        override protected def getIndexer(indexName: String): Indexer = mockIndexer

        override protected def getS3Client: AmazonS3 = {
          val m = mock[AmazonS3]
          val fakeMd = new ObjectMetadata()
          fakeMd.setContentType("video/mp4")
          fakeMd.setContentLength(1234L)
          fakeMd.setLastModified(Date.from(Instant.now()))

          m.getObjectMetadata("my-bucket","path/to/object").returns(fakeMd)
          m
        }
      }

      try {
        test.handleRequest(fakeEvent, fakeContext)
      } catch {
        case ex:Throwable=>
          ex.printStackTrace()
          throw ex
      }
      there was one(mockSendIngestedMessage).apply(any)
    }
  }

  "InputLambdaMain.handleRestore" should {
    "update any open/pending jobs that refer to the file in question but leave ones that are not RESTORE" in {
      val mockDao = mock[JobModelDAO]
      val mockEntity = mock[S3EventNotification.S3Entity]
      mockEntity.getBucket returns (mock[S3EventNotification.S3BucketEntity].getName returns "test-bucket")
      mockEntity.getObject returns (mock[S3EventNotification.S3ObjectEntity].getKey returns "path/to/file")

      val mockRecord = mock[S3EventNotification.S3EventNotificationRecord]
      mockRecord.getS3 returns mockEntity

      val job1 = JobModel("test-job-1","RESTORE",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,"test-source-id",None,SourceType.SRC_MEDIA)
      val job2 = JobModel("test-job-2","TRANSCODE",Some(ZonedDateTime.now()),None,JobStatus.ST_RUNNING,None,"test-source-id",None,SourceType.SRC_MEDIA)
      val job3 = JobModel("test-job-3","RESTORE",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,"test-source-id",None,SourceType.SRC_MEDIA)

      mockDao.jobsForSource("test-source-id") returns Future(List(Right(job1),Right(job2),Right(job3)))
      mockDao.putJob(any[JobModel]) returns Future(None)
      val test = new InputLambdaMain {
        override protected def getJobModelDAO: JobModelDAO = mockDao

        override def makeDocId(bucket:String,path:String) = "test-source-id"
      }

      Await.ready(test.handleRestored(mockRecord), 5 seconds)
      there were two(mockDao).putJob(any[JobModel])
    }

  }

  "InputLambdaMain.sendIngestedMessage" should {
    "send a message to SQS containing the created entry" in {
      val testEntry = ArchiveEntry(
        "test-id",
        "fake-bucket",
        "/path/to/file",
        Some("region"),
        Some(".ext"),
        1234L,
        ZonedDateTime.now(),
        "fake-etag",
        MimeType("video","mp4"),
        false,
        StorageClass.STANDARD,
        Seq(),
        false,
        None
      )

      val mockSqsClient = mock[AmazonSQS]
      val mockedResult = new SendMessageResult().withMessageId("fake-message-id")
      mockSqsClient.sendMessage(any[SendMessageRequest]) returns mockedResult

      val mockS3Client = mock[AmazonS3]
      mockS3Client.getRegionName returns "region"

      val test = new InputLambdaMain {
        override protected def getSqsClient() = mockSqsClient
        override protected def getS3Client() = mockS3Client
        override protected def getNotificationQueue() = "fake-queue"
      }

      val expectedMessageRequest = new SendMessageRequest()
        .withQueueUrl("fake-queue")
        .withMessageBody(IngestMessage(testEntry,"test-id").asJson.toString())

      test.sendIngestedMessage(testEntry)
      there was one(mockSqsClient).sendMessage(expectedMessageRequest)
    }
  }
}
