package TestFileMove

import akka.actor.Props
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, ProxyLocation, ProxyType, StorageClass}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.FileMove.{DeleteOriginalFiles, GenericMoveActor}
import akka.pattern.ask
import play.api.Configuration
import services.FileMove.GenericMoveActor.{MoveActorMessage, PerformStep, StepFailed, StepSucceeded}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{DeleteObjectRequest, HeadObjectResponse}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

class DeleteOriginalFileSpec extends Specification with Mockito {
  sequential
  implicit val timeout:akka.util.Timeout = 30.seconds
  import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._

  "DeleteOriginalFiles!PerformStep" should {
    "validate that the destination files exist then tell S3 to delete the original source and proxy files" in new AkkaTestkitSpecs2Support {
      val mockedS3Client = mock[S3Client]
      mockedS3Client.doesObjectExist(any,any) returns Success(true)
      val fakeMetadata = HeadObjectResponse.builder()
        .contentLength(12345L)
        .build()

      mockedS3Client.getObjectMetadata(any,any, any) returns Success(fakeMetadata)
      val mockedS3ClientMgr = mock[S3ClientManager]
      mockedS3ClientMgr.getS3Client(any,any) returns mockedS3Client
      val mockedIndexer = mock[Indexer]

      val actor = system.actorOf(Props(new DeleteOriginalFiles(mockedS3ClientMgr, mockedIndexer, Configuration.empty)))

      val originalSourceEntry = mock[ArchiveEntry]
      originalSourceEntry.bucket returns "source-media-bucket"
      originalSourceEntry.path returns "path/to/source-media.mxf"

      val sourceProxyList = Seq(
        ProxyLocation("file-id-1","source-proxy-1",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/thumbnail",None,StorageClass.STANDARD),
        ProxyLocation("file-id-2","source-proxy-2",ProxyType.VIDEO,"source-proxy-bucket","path/to/vidproxy",None,StorageClass.STANDARD)
      )
      val destProxyList = Seq(
        ProxyLocation("file-id-3","dest-proxy-1",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/thumbnail",None,StorageClass.STANDARD),
        ProxyLocation("file-id-4","dest-proxy-2",ProxyType.VIDEO,"dest-proxy-bucket","path/to/vidproxy",None,StorageClass.STANDARD)
      )
      val jobState = GenericMoveActor.FileMoveTransientData(
        "source-file-id",
        Some(originalSourceEntry),
        Some("dest-file-id"),
        Some(sourceProxyList),
        Some(destProxyList),
        "dest-bucket",
        "dest-proxy-bucket",
        "dest-region"
      )
      val result = Await.result((actor ? PerformStep(jobState) ).mapTo[MoveActorMessage], 30 seconds)

      val expectedFinalState = jobState.copy(sourceFileProxies = None)
      result mustEqual StepSucceeded(expectedFinalState)
      there was one(mockedS3Client).doesObjectExist("dest-bucket","path/to/source-media.mxf")
      there was one(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/thumbnail")
      there was one(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/vidproxy")

      val expectedDeleteRequests = Seq(
        DeleteObjectRequest.builder().bucket("source-media-bucket").key("path/to/source-media.mxf").build(),
        DeleteObjectRequest.builder().bucket("source-proxy-bucket").key("path/to/thumbnail").build(),
        DeleteObjectRequest.builder().bucket("source-proxy-bucket").key("path/to/vidproxy").build()
      )
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests.head)
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests(1))
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests(2))
    }

    "not delete anything if the main media size is different" in new AkkaTestkitSpecs2Support {
      val mockedS3Client = mock[S3Client]
      mockedS3Client.doesObjectExist(any,any) returns Success(true)

      val fakeMetadata = HeadObjectResponse.builder().contentLength(12345L).build()
      val fakeOtherMetadata = HeadObjectResponse.builder().contentLength(3L).build()

      mockedS3Client.getObjectMetadata(any,any,any) returns Success(fakeOtherMetadata) thenReturns Success(fakeMetadata)

      val mockedS3ClientMgr = mock[S3ClientManager]
      mockedS3ClientMgr.getS3Client(any,any) returns mockedS3Client
      val mockedIndexer = mock[Indexer]

      val actor = system.actorOf(Props(new DeleteOriginalFiles(mockedS3ClientMgr, mockedIndexer, Configuration.empty)))

      val originalSourceEntry = mock[ArchiveEntry]
      originalSourceEntry.bucket returns "source-media-bucket"
      originalSourceEntry.path returns "path/to/source-media.mxf"

      val sourceProxyList = Seq(
        ProxyLocation("file-id-1","source-proxy-1",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/thumbnail",None,StorageClass.STANDARD),
        ProxyLocation("file-id-2","source-proxy-2",ProxyType.VIDEO,"source-proxy-bucket","path/to/vidproxy",None,StorageClass.STANDARD)
      )
      val destProxyList = Seq(
        ProxyLocation("file-id-3","dest-proxy-1",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/thumbnail",None,StorageClass.STANDARD),
        ProxyLocation("file-id-4","dest-proxy-2",ProxyType.VIDEO,"dest-proxy-bucket","path/to/vidproxy",None,StorageClass.STANDARD)
      )
      val jobState = GenericMoveActor.FileMoveTransientData(
        "source-file-id",
        Some(originalSourceEntry),
        Some("dest-file-id"),
        Some(sourceProxyList),
        Some(destProxyList),
        "dest-bucket",
        "dest-proxy-bucket",
        "dest-region"
      )
      val result = Await.result((actor ? PerformStep(jobState) ).mapTo[MoveActorMessage], 30 seconds)

      val expectedFinalState = jobState.copy(sourceFileProxies = None)
      result must beAnInstanceOf[StepFailed]
      there was one(mockedS3Client).getObjectMetadata("source-media-bucket","path/to/source-media.mxf", None)
      there was one(mockedS3Client).getObjectMetadata("dest-bucket","path/to/source-media.mxf",None)
      there was one(mockedS3Client).doesObjectExist("dest-bucket","path/to/source-media.mxf")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/thumbnail")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/vidproxy")
      there was no(mockedS3Client).deleteObject(DeleteObjectRequest.builder().bucket("source-media-bucket").key("path/to/source-media.mxf").build())
      there was no(mockedS3Client).deleteObject(DeleteObjectRequest.builder().bucket("source-proxy-bucket").key("path/to/thumbnail").build())
      there was no(mockedS3Client).deleteObject(DeleteObjectRequest.builder().bucket("source-proxy-bucket").key("path/to/vidproxy").build())
    }

    "not delete anything if the main media checksum is different" in new AkkaTestkitSpecs2Support {
      val mockedS3Client = mock[S3Client]
      mockedS3Client.doesObjectExist(any,any) returns Success(true)

      val fakeMetadata = HeadObjectResponse.builder().contentLength(12345L).build()
      val fakeOtherMetadata = HeadObjectResponse.builder().contentLength(12345L).build()

      mockedS3Client.getObjectMetadata(any,any,any) returns Success(fakeOtherMetadata) thenReturns Success(fakeMetadata)

      val mockedS3ClientMgr = mock[S3ClientManager]
      mockedS3ClientMgr.getS3Client(any,any) returns mockedS3Client
      val mockedIndexer = mock[Indexer]

      val actor = system.actorOf(Props(new DeleteOriginalFiles(mockedS3ClientMgr, mockedIndexer, Configuration.empty)))

      val originalSourceEntry = mock[ArchiveEntry]
      originalSourceEntry.bucket returns "source-media-bucket"
      originalSourceEntry.path returns "path/to/source-media.mxf"

      val sourceProxyList = Seq(
        ProxyLocation("file-id-1","source-proxy-1",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/thumbnail",None,StorageClass.STANDARD),
        ProxyLocation("file-id-2","source-proxy-2",ProxyType.VIDEO,"source-proxy-bucket","path/to/vidproxy",None,StorageClass.STANDARD)
      )
      val destProxyList = Seq(
        ProxyLocation("file-id-3","dest-proxy-1",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/thumbnail",None,StorageClass.STANDARD),
        ProxyLocation("file-id-4","dest-proxy-2",ProxyType.VIDEO,"dest-proxy-bucket","path/to/vidproxy",None,StorageClass.STANDARD)
      )
      val jobState = GenericMoveActor.FileMoveTransientData(
        "source-file-id",
        Some(originalSourceEntry),
        Some("dest-file-id"),
        Some(sourceProxyList),
        Some(destProxyList),
        "dest-bucket",
        "dest-proxy-bucket",
        "dest-region"
      )
      val result = Await.result((actor ? PerformStep(jobState) ).mapTo[MoveActorMessage], 30 seconds)

      val expectedFinalState = jobState.copy(sourceFileProxies = None)
      result must beAnInstanceOf[StepFailed]
      there was one(mockedS3Client).getObjectMetadata("source-media-bucket","path/to/source-media.mxf", None)
      there was one(mockedS3Client).getObjectMetadata("dest-bucket","path/to/source-media.mxf", None)
      there was one(mockedS3Client).doesObjectExist("dest-bucket","path/to/source-media.mxf")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/thumbnail")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/vidproxy")
      there was no(mockedS3Client).deleteObject(DeleteObjectRequest.builder().bucket("source-media-bucket").key("path/to/source-media.mxf").build())
      there was no(mockedS3Client).deleteObject(DeleteObjectRequest.builder().bucket("source-proxy-bucket").key("path/to/thumbnail").build())
      there was no(mockedS3Client).deleteObject(DeleteObjectRequest.builder().bucket("source-proxy-bucket").key("path/to/vidproxy").build())
    }
  }
}
