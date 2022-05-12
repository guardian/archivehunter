import java.time.{Instant, ZonedDateTime}
import java.util.Date
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification._
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient, RequestSuccess}
import com.theguardian.multimedia.archivehunter.common._
import org.specs2.mock.Mockito
import org.specs2.mutable._
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{SendMessageRequest, SendMessageResult}
import com.sksamuel.elastic4s.embedded.LocalNode
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import io.circe.syntax._
import io.circe.generic.auto._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, HeadObjectResponse}

import collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Success
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.Materializer

@RunWith(classOf[JUnitRunner])
class InputLambdaMainSpec extends Specification with Mockito with ZonedDateTimeEncoder with StorageClassEncoder {
  sequential

  implicit val system:ActorSystem = ActorSystem("root")
  implicit val mat:Materializer = Materializer.matFromSystem

  "InputLambdaMain" should {
    "handle paths with spaces encoded to +" in {
      val fakeEvent = new S3Event(Seq(
        new S3EventNotificationRecord("aws-fake-region","ObjectCreated:Put","unit_test",
          "2018-01-01T11:12:13.000Z","1",
          new RequestParametersEntity("localhost"),
          new ResponseElementsEntity("none","fake-req-id"),
          new S3Entity("fake-config-id",
            new S3BucketEntity("my-bucket", new UserIdentityEntity("owner"),"arn"),
            new S3ObjectEntity("path/to/object+with+spaces",1234L,"fakeEtag","v1"),"1"),
          new UserIdentityEntity("no-principal"))
      ).asJava)

      val fakeContext = mock[Context]
      val mockIndexer = mock[Indexer]
      val mockSendIngestedMessage = mock[ArchiveEntry=>Unit]

      mockIndexer.indexSingleItem(any,any)(any).returns(Future(Right("fake-entry-id")))
      mockIndexer.getById(any)(any) returns Future(mock[ArchiveEntry])
      val mockWritePathCacheEntries = mock[Seq[PathCacheEntry]=>Future[Unit]]
      mockWritePathCacheEntries.apply(any) returns Future( () )

      val mockClient = mock[S3Client]
      val test = new InputLambdaMain {
        override def getRegionFromEnvironment: Option[Region] = Some(Region.AP_EAST_1)
        override protected def getElasticClient(clusterEndpoint: String): ElasticClient = mock[ElasticClient]

        override def sendIngestedMessage(entry:ArchiveEntry) = mockSendIngestedMessage(entry)

        override protected def getClusterEndpoint = "testClusterEndpoint"

        override protected def getIndexName = "testIndexName"

        override protected def getIndexer(indexName: String): Indexer = mockIndexer

        override def writePathCacheEntries(newCacheEntries: Seq[PathCacheEntry])(implicit pathCacheIndexer: PathCacheIndexer, elasticClient: ElasticClient): Future[Unit] =
          mockWritePathCacheEntries(newCacheEntries)

        override protected def getS3Client: S3Client = {
          val fakeMd = HeadObjectResponse.builder().contentType("video/mp4").contentLength(1234L).lastModified(Instant.now()).build()
          mockClient.headObject(org.mockito.ArgumentMatchers.any[HeadObjectRequest]).returns(fakeMd)
          mockClient
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
      there was one(mockWritePathCacheEntries).apply(any)
      there was one(mockIndexer).indexSingleItem(any,any)(any)
      there was one(mockClient).headObject(HeadObjectRequest.builder().bucket("my-bucket").key("path/to/object with spaces").versionId("v1").build())
    }

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
      mockIndexer.indexSingleItem(any,any)(any).returns(Future(Right("fake-entry-id")))
      mockIndexer.getById(any)(any) returns Future(mock[ArchiveEntry])

      val mockSendIngestedMessage = mock[ArchiveEntry=>Unit]
      val mockESClient = mock[ElasticClient]

      val mockWritePathCacheEntries = mock[Seq[PathCacheEntry]=>Future[Unit]]
      mockWritePathCacheEntries.apply(any) returns Future( () )

      val mockClient = mock[S3Client]
      val test = new InputLambdaMain {
        override def getRegionFromEnvironment: Option[Region] = Some(Region.AP_EAST_1)

        override protected def getElasticClient(clusterEndpoint: String): ElasticClient = mockESClient

        override def sendIngestedMessage(entry:ArchiveEntry) = mockSendIngestedMessage(entry)

        override protected def getClusterEndpoint = "testClusterEndpoint"

        override protected def getIndexName = "testIndexName"

        override protected def getIndexer(indexName: String): Indexer = mockIndexer

        override def writePathCacheEntries(newCacheEntries: Seq[PathCacheEntry])(implicit pathCacheIndexer: PathCacheIndexer, elasticClient: ElasticClient): Future[Unit] =
          mockWritePathCacheEntries(newCacheEntries)

        override protected def getS3Client: S3Client = {
          val fakeMd = HeadObjectResponse.builder().contentType("video/mp4").contentLength(1234L).lastModified(Instant.now()).build()

          //"my-bucket","path/to/object"
          mockClient.headObject(org.mockito.ArgumentMatchers.any[HeadObjectRequest]).returns(fakeMd)
          mockClient
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
      there was one(mockWritePathCacheEntries).apply(any)
      there was one(mockIndexer).indexSingleItem(any,any)(any)
      there was one(mockClient).headObject(HeadObjectRequest.builder().bucket("my-bucket").key("path/to/object").versionId("v1").build())
    }
  }

  "InputLambdaMain.handleRestore" should {
    "update any open/pending jobs that refer to the file in question but leave ones that are not RESTORE" in {
      val mockBucketEntity = mock[S3EventNotification.S3BucketEntity]
      mockBucketEntity.getName returns "test-bucket"
      val mockObjectEntity = mock[S3EventNotification.S3ObjectEntity]
      mockObjectEntity.getKey returns "path/to/file"
      val mockDao = mock[JobModelDAO]
      val mockEntity = mock[S3EventNotification.S3Entity]
      mockEntity.getBucket returns mockBucketEntity
      mockEntity.getObject returns mockObjectEntity

      val mockRecord = mock[S3EventNotification.S3EventNotificationRecord]
      mockRecord.getS3 returns mockEntity

      val job1 = JobModel("test-job-1","RESTORE",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,"test-source-id",None,SourceType.SRC_MEDIA, None)
      val job2 = JobModel("test-job-2","TRANSCODE",Some(ZonedDateTime.now()),None,JobStatus.ST_RUNNING,None,"test-source-id",None,SourceType.SRC_MEDIA, None)
      val job3 = JobModel("test-job-3","RESTORE",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,"test-source-id",None,SourceType.SRC_MEDIA, None)

      mockDao.jobsForSource("test-source-id") returns Future(List(Right(job1),Right(job2),Right(job3)))
      mockDao.putJob(any[JobModel]) returns Future(None)
      val test = new InputLambdaMain {
        override protected def getIndexName: String = "test-index"
        override protected def getClusterEndpoint: String = "localhost:9200"
        override protected def getJobModelDAO: JobModelDAO = mockDao

        override def makeDocId(bucket:String,path:String) = "test-source-id"
      }

      Await.ready(test.handleRestored(mockRecord,"somepath/to/media"), 5.seconds)
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
        None,
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

      val mockS3Client = mock[S3Client]

      val test = new InputLambdaMain {
        override protected def getIndexName: String = "test-index"
        override protected def getClusterEndpoint: String = "localhost:9200"
        override protected def getSqsClient() = mockSqsClient
        override protected def getS3Client() = mockS3Client
        override protected def getNotificationQueue() = "fake-queue"

        override def getRegionFromEnvironment: Option[Region] = Some(Region.AP_EAST_1)
      }

      val expectedMessageRequest = new SendMessageRequest()
        .withQueueUrl("fake-queue")
        .withMessageBody(IngestMessage(testEntry,"test-id").asJson.toString())

      test.sendIngestedMessage(testEntry)
      there was one(mockSqsClient).sendMessage(expectedMessageRequest)
    }
  }

  "InputLambdaMain.handleStarted" should {
    "update any open/pending jobs that refer to the file in question with the ST_RUNNING status" in {
      val mockBucketEntity = mock[S3EventNotification.S3BucketEntity]
      mockBucketEntity.getName returns "test-bucket"
      val mockObjectEntity = mock[S3EventNotification.S3ObjectEntity]
      mockObjectEntity.getKey returns "path/to/file"
      val mockDao = mock[JobModelDAO]
      val mockEntity = mock[S3EventNotification.S3Entity]
      mockEntity.getBucket returns mockBucketEntity
      mockEntity.getObject returns mockObjectEntity

      val mockRecord = mock[S3EventNotification.S3EventNotificationRecord]
      mockRecord.getS3 returns mockEntity

      val date1 = Some(ZonedDateTime.now())
      val date3 = Some(ZonedDateTime.now())

      val job1 = JobModel("test-job-1", "RESTORE", date1, None, JobStatus.ST_PENDING, None, "test-source-id", None, SourceType.SRC_MEDIA, None)
      val job2 = JobModel("test-job-2", "TRANSCODE", Some(ZonedDateTime.now()), None, JobStatus.ST_RUNNING, None, "test-source-id", None, SourceType.SRC_MEDIA, None)
      val job3 = JobModel("test-job-3", "RESTORE", date3, None, JobStatus.ST_PENDING, None, "test-source-id", None, SourceType.SRC_MEDIA, None)

      mockDao.jobsForSource("test-source-id") returns Future(List(Right(job1), Right(job2), Right(job3)))
      mockDao.putJob(any[JobModel]) returns Future(None)

      val test = new InputLambdaMain {
        override protected def getIndexName: String = "test-index"
        override protected def getClusterEndpoint: String = "localhost:9200"
        override protected def getJobModelDAO: JobModelDAO = mockDao

        override def makeDocId(bucket: String, path: String) = "test-source-id"
      }

      Await.ready(test.handleStarted(mockRecord, "somepath/to/media"), 5.seconds)
      there was one(mockDao).putJob(JobModel("test-job-1","RESTORE",date1,None,JobStatus.ST_RUNNING,None,"test-source-id",None,SourceType.SRC_MEDIA,None))
      there was one(mockDao).putJob(JobModel("test-job-3","RESTORE",date3,None,JobStatus.ST_RUNNING,None,"test-source-id",None,SourceType.SRC_MEDIA,None))
    }
  }

  "InputLambdaMain.handleExpired" should {
    "update any lightbox entries that refer to the file in question with the RS_EXPIRED status" in {
      val mockBucketEntity = mock[S3EventNotification.S3BucketEntity]
      mockBucketEntity.getName returns "test-bucket"
      val mockObjectEntity = mock[S3EventNotification.S3ObjectEntity]
      mockObjectEntity.getKey returns "path/to/file"
      val mockDao = mock[LightboxEntryDAO]
      val mockEntity = mock[S3EventNotification.S3Entity]
      mockEntity.getBucket returns mockBucketEntity
      mockEntity.getObject returns mockObjectEntity

      val mockRecord = mock[S3EventNotification.S3EventNotificationRecord]
      mockRecord.getS3 returns mockEntity

      val date1 = Some(ZonedDateTime.now())
      val date2 = ZonedDateTime.now()

      val entry1 = LightboxEntry("test@test.org", "test-file-id", date2, RestoreStatus.RS_SUCCESS, date1, date1, date1, None, None)
      val entry2 = LightboxEntry("test2@test.org", "test-file-id", date2, RestoreStatus.RS_SUCCESS, date1, date1, date1, None, None)
      val entry3 = LightboxEntry("test3@test.org", "test-file-id", date2, RestoreStatus.RS_SUCCESS, date1, date1, date1, None, None)

      mockDao.getFilesForId("test-file-id") returns Future(List(Right(entry1), Right(entry2), Right(entry3)))

      val test = new InputLambdaMain {
        override protected def getIndexName: String = "test-index"
        override protected def getClusterEndpoint: String = "localhost:9200"
        override protected def getLightboxEntryDAO: LightboxEntryDAO = mockDao

        override def makeDocId(bucket: String, path: String) = "test-file-id"
      }

      Await.ready(test.handleExpired(mockRecord, "somepath/to/media"), 5.seconds)
      there was one(mockDao).put(LightboxEntry("test@test.org", "test-file-id", date2, RestoreStatus.RS_EXPIRED, date1, date1, date1, None, None))
      there was one(mockDao).put(LightboxEntry("test2@test.org", "test-file-id", date2, RestoreStatus.RS_EXPIRED, date1, date1, date1, None, None))
      there was one(mockDao).put(LightboxEntry("test3@test.org", "test-file-id", date2, RestoreStatus.RS_EXPIRED, date1, date1, date1, None, None))
    }
  }

  "InputLambdaMain.getObjectVersion" should {
    "return a Some populated with the correct string if the version identity is present" in {
      val fakeEvent = new S3EventNotificationRecord("aws-fake-region","ObjectCreated:Put","unit_test",
          "2018-01-01T11:12:13.000Z","1",
          new RequestParametersEntity("localhost"),
          new ResponseElementsEntity("none","fake-req-id"),
          new S3Entity("fake-config-id",
            new S3BucketEntity("my-bucket", new UserIdentityEntity("owner"),"arn"),
            new S3ObjectEntity("path/to/object",1234L,"fakeEtag","v1"),"1"),
          new UserIdentityEntity("no-principal"))
      val test = new InputLambdaMain {
        override protected def getIndexName: String = "test-index"
        override protected def getClusterEndpoint: String = "localhost:9200"
      }
      val maybeVersionId = test.getObjectVersion(fakeEvent)
      maybeVersionId must beSome("v1")
    }
    "return a None if the version identity is an empty string" in {
      val fakeEvent = new S3EventNotificationRecord("aws-fake-region","ObjectCreated:Put","unit_test",
        "2018-01-01T11:12:13.000Z","1",
        new RequestParametersEntity("localhost"),
        new ResponseElementsEntity("none","fake-req-id"),
        new S3Entity("fake-config-id",
          new S3BucketEntity("my-bucket", new UserIdentityEntity("owner"),"arn"),
          new S3ObjectEntity("path/to/object",1234L,"fakeEtag",""),"1"),
        new UserIdentityEntity("no-principal"))
      val test = new InputLambdaMain {
        override protected def getIndexName: String = "test-index"
        override protected def getClusterEndpoint: String = "localhost:9200"
      }
      val maybeVersionId = test.getObjectVersion(fakeEvent)
      maybeVersionId must beNone
    }
    "return a None if the version identity is set to null" in {
      val fakeEvent = new S3EventNotificationRecord("aws-fake-region","ObjectCreated:Put","unit_test",
        "2018-01-01T11:12:13.000Z","1",
        new RequestParametersEntity("localhost"),
        new ResponseElementsEntity("none","fake-req-id"),
        new S3Entity("fake-config-id",
          new S3BucketEntity("my-bucket", new UserIdentityEntity("owner"),"arn"),
          new S3ObjectEntity("path/to/object",1234L,"fakeEtag",null),"1"),
        new UserIdentityEntity("no-principal"))
      val test = new InputLambdaMain {
        override protected def getIndexName: String = "test-index"
        override protected def getClusterEndpoint: String = "localhost:9200"
      }
      val maybeVersionId = test.getObjectVersion(fakeEvent)
      maybeVersionId must beNone
    }
  }
}
