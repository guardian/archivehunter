package TestFileMove

import java.time.ZonedDateTime
import akka.actor.Props
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, DocId, MimeType, StorageClass}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.FileMove.{CopyMainFile, ImprovedLargeFileCopier}
import services.FileMove.GenericMoveActor._
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{CopyObjectRequest, CopyObjectResponse, CopyObjectResult, DeleteObjectRequest, DeleteObjectsRequest, DeleteObjectsResponse, HeadObjectResponse}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

class CopyMainFileSpec extends Specification with Mockito with DocId {
  import akka.pattern.ask
  import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._
  implicit val timeout:akka.util.Timeout = 30 seconds

  "CopyMainFile!PerformStep" should {
    "tell S3 to copy the file with same path to given destination bucket" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[S3Client]
      val mockedCopyResult = CopyObjectResponse.builder().build()
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]

      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.copyObject(org.mockito.ArgumentMatchers.any[CopyObjectRequest]) returns mockedCopyResult

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,None, 1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies","dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? PerformStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]
      result.asInstanceOf[StepSucceeded].updatedData.destFileId must beSome(makeDocId("destBucket","/path/to/file"))
      val expectedCopyRequest = CopyObjectRequest.builder()
        .destinationBucket("sourcebucket")
        .destinationKey("/path/to/file")
        .sourceBucket("destBucket")
        .sourceKey("/path/to/file")
        .build()
      there was one(mockedS3Client).copyObject(expectedCopyRequest)
    }

    "reply with step failed if the S3 copy raises an exception" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[S3Client]
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]
      mockedClientMgr.getS3Client(any,any) returns mockedS3Client

      mockedS3Client.copyObject(org.mockito.ArgumentMatchers.any[CopyObjectRequest]) throws new RuntimeException("test")

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies", "dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? PerformStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepFailed]
      result.asInstanceOf[StepFailed].updatedData.destFileId must beNone
      //there was one(mockedS3Client).copyObject("sourcebucket","/path/to/file","destBucket","/path/to/file")
      val expectedReq = CopyObjectRequest.builder()
        .sourceBucket("sourcebucket")
        .sourceKey("/path/to/file")
        .destinationBucket("destbucket")
        .destinationKey("/path/to/file")
        .build()
      there was one(mockedS3Client).copyObject(expectedReq)
    }
  }

  "CopyMainFile!RollbackStep" should {
    "delete the target file if the original exists" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[S3Client]
      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      val mockedGetResponse = mock[HeadObjectResponse]
      val mockedDeleteResponse = mock[DeleteObjectsResponse]
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]

      mockedS3Client.doesObjectExist(any, any) returns Success(true)

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies","dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? RollbackStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]
      there were two(mockedClientMgr).getS3Client(any,any)
      there was one(mockedS3Client).doesObjectExist("sourcebucket","/path/to/file")
      val expectedDeleteReq = DeleteObjectRequest.builder().bucket("destBucket").key("/path/to/file").build()
      there was one(mockedS3Client).deleteObject(expectedDeleteReq)
    }

    "copy the target file back to the source if it does not exist" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[S3Client]
      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      val mockedLargeFileCopier = mock[ImprovedLargeFileCopier]

      mockedS3Client.doesObjectExist(any,any) returns Success(false)
      val mockedCopyResult = CopyObjectResponse.builder().copyObjectResult(CopyObjectResult.builder().eTag("some-etag").build()).build()
      mockedS3Client.copyObject(org.mockito.ArgumentMatchers.any[CopyObjectRequest]) returns mockedCopyResult

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      val request = FileMoveTransientData("fake-id",Some(testItem),None,None,None,"destBucket","destProxies","dest-region")

      val actor = system.actorOf(Props(new CopyMainFile(mockedClientMgr, Configuration.empty, mockedLargeFileCopier)))

      val result = Await.result(actor ? RollbackStep(request), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]
      result.asInstanceOf[StepSucceeded].updatedData.destFileId must beNone
      there were two(mockedClientMgr).getS3Client(any,any)
      there was one(mockedS3Client).doesObjectExist("sourcebucket","/path/to/file")
      val expectedCopyRequest = CopyObjectRequest.builder()
        .destinationBucket("destbucket")
        .destinationKey("/path/to/file")
        .sourceBucket("sourcebucket")
        .sourceKey("/path/to/file")
        .build()
      there was one(mockedS3Client).copyObject(expectedCopyRequest)

      there was one(mockedS3Client).deleteObject(org.mockito.ArgumentMatchers.any[DeleteObjectRequest])
    }
  }
}
