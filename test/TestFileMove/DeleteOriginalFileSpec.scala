package TestFileMove

import akka.actor.Props
import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, ProxyLocation, ProxyType, StorageClass}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.FileMove.{DeleteOriginalFiles, GenericMoveActor}
import akka.pattern.ask
import com.amazonaws.services.s3.model.ObjectMetadata
import play.api.Configuration
import services.FileMove.GenericMoveActor.{MoveActorMessage, PerformStep, StepFailed, StepSucceeded}

import scala.concurrent.Await
import scala.concurrent.duration._

class DeleteOriginalFileSpec extends Specification with Mockito {
  sequential
  implicit val timeout:akka.util.Timeout = 30.seconds

  "DeleteOriginalFiles!PerformStep" should {
    "validate that the destination files exist then tell S3 to delete the original source and proxy files" in new AkkaTestkitSpecs2Support {
      val mockedS3Client = mock[AmazonS3]
      mockedS3Client.doesObjectExist(any,any) returns true
      val fakeMetadata = mock[ObjectMetadata]
      fakeMetadata.getContentLength returns 12345L
      fakeMetadata.getContentMD5 returns "some-md5-here"
      mockedS3Client.getObjectMetadata(any,any) returns fakeMetadata
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
      there was one(mockedS3Client).deleteObject("source-media-bucket","path/to/source-media.mxf")
      there was one(mockedS3Client).deleteObject("source-proxy-bucket","path/to/thumbnail")
      there was one(mockedS3Client).deleteObject("source-proxy-bucket", "path/to/vidproxy")
    }

    "not delete anything if the main media size is different" in new AkkaTestkitSpecs2Support {
      val mockedS3Client = mock[AmazonS3]
      mockedS3Client.doesObjectExist(any,any) returns true

      val fakeMetadata = mock[ObjectMetadata]
      fakeMetadata.getContentLength returns 12345L
      fakeMetadata.getContentMD5 returns "some-md5-here"
      val fakeOtherMetadata = mock[ObjectMetadata]
      fakeOtherMetadata.getContentLength returns 3L
      fakeMetadata.getContentMD5 returns "some-md5-here"

      mockedS3Client.getObjectMetadata(any,any) returns fakeOtherMetadata thenReturns fakeMetadata

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
      there was one(mockedS3Client).getObjectMetadata("source-media-bucket","path/to/source-media.mxf")
      there was one(mockedS3Client).getObjectMetadata("dest-bucket","path/to/source-media.mxf")
      there was one(mockedS3Client).doesObjectExist("dest-bucket","path/to/source-media.mxf")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/thumbnail")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/vidproxy")
      there was no(mockedS3Client).deleteObject("source-media-bucket","path/to/source-media.mxf")
      there was no(mockedS3Client).deleteObject("source-proxy-bucket","path/to/thumbnail")
      there was no(mockedS3Client).deleteObject("source-proxy-bucket", "path/to/vidproxy")
    }

    "not delete anything if the main media checksum is different" in new AkkaTestkitSpecs2Support {
      val mockedS3Client = mock[AmazonS3]
      mockedS3Client.doesObjectExist(any,any) returns true

      val fakeMetadata = mock[ObjectMetadata]
      fakeMetadata.getContentLength returns 12345L
      fakeMetadata.getContentMD5 returns "some-md5-here"
      val fakeOtherMetadata = mock[ObjectMetadata]
      fakeOtherMetadata.getContentLength returns 12345L
      fakeOtherMetadata.getContentMD5 returns "some-other-md5-here"

      mockedS3Client.getObjectMetadata(any,any) returns fakeOtherMetadata thenReturns fakeMetadata

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
      there was one(mockedS3Client).getObjectMetadata("source-media-bucket","path/to/source-media.mxf")
      there was one(mockedS3Client).getObjectMetadata("dest-bucket","path/to/source-media.mxf")
      there was one(mockedS3Client).doesObjectExist("dest-bucket","path/to/source-media.mxf")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/thumbnail")
      there was no(mockedS3Client).doesObjectExist("dest-proxy-bucket","path/to/vidproxy")
      there was no(mockedS3Client).deleteObject("source-media-bucket","path/to/source-media.mxf")
      there was no(mockedS3Client).deleteObject("source-proxy-bucket","path/to/thumbnail")
      there was no(mockedS3Client).deleteObject("source-proxy-bucket", "path/to/vidproxy")
    }
  }
}
