package TestFileMove

import java.time.ZonedDateTime
import akka.actor.Props
import com.amazonaws.AmazonClientException
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.internal.DeleteObjectsResponse
import com.amazonaws.services.s3.model.{CopyObjectResult, ObjectMetadata}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, DocId, MimeType, StorageClass}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.FileMove.{CopyMainFile, ImprovedLargeFileCopier}
import services.FileMove.GenericMoveActor._

import scala.concurrent.Await
import scala.concurrent.duration._

class CopyMainFileSpec extends Specification with Mockito with DocId {
  import akka.pattern.ask
  implicit val timeout:akka.util.Timeout = 30 seconds

  "CopyMainFile!PerformStep" should {
    "tell S3 to copy the file with same path to given destination bucket" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      val mockedCopyResult = mock[CopyObjectResult]
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]

      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.copyObject(any, any, any, any) returns mockedCopyResult

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies","dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? PerformStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]
      result.asInstanceOf[StepSucceeded].updatedData.destFileId must beSome(makeDocId("destBucket","/path/to/file"))
      there was one(mockedS3Client).copyObject("sourcebucket","/path/to/file","destBucket","/path/to/file")
    }

    "reply with step failed if the S3 copy raises an exception" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]
      mockedClientMgr.getS3Client(any,any) returns mockedS3Client

      mockedS3Client.copyObject(any, any, any, any) throws new RuntimeException("test")

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies", "dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? PerformStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepFailed]
      result.asInstanceOf[StepFailed].updatedData.destFileId must beNone
      there was one(mockedS3Client).copyObject("sourcebucket","/path/to/file","destBucket","/path/to/file")
    }
  }

  "CopyMainFile!RollbackStep" should {
    "delete the target file if the original exists" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      val mockedGetResponse = mock[ObjectMetadata]
      val mockedDeleteResponse = mock[DeleteObjectsResponse]
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]

      mockedS3Client.deleteObject(any, any)
      mockedS3Client.doesObjectExist(any, any) returns true

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies","dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? RollbackStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]
      there were two(mockedClientMgr).getS3Client(any,any)
      there was one(mockedS3Client).doesObjectExist("sourcebucket","/path/to/file")
      there was one(mockedS3Client).deleteObject("destBucket","/path/to/file")
    }

    "copy the target file back to the source if it does not exist" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]

      mockedS3Client.doesObjectExist(any,any) returns false
      val mockedCopyResult = new CopyObjectResult()
      mockedCopyResult.setETag("some-etag")
      mockedS3Client.copyObject(any, any,any,any) returns mockedCopyResult

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies","dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? RollbackStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]
      result.asInstanceOf[StepSucceeded].updatedData.destFileId must beNone
      there were two(mockedClientMgr).getS3Client(any,any)
      there was one(mockedS3Client).doesObjectExist("sourcebucket","/path/to/file")
      there was one(mockedS3Client).copyObject("destBucket","/path/to/file","sourcebucket","/path/to/file")
      there was one(mockedS3Client).deleteObject(any, any)
    }
  }
}
