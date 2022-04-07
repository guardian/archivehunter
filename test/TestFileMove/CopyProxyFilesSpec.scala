package TestFileMove

import akka.actor.Props
import com.theguardian.multimedia.archivehunter.common.{DocId, ProxyLocation, ProxyType, StorageClass}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.FileMove.CopyProxyFiles
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{CopyObjectRequest, CopyObjectResponse, DeleteObjectRequest, HeadObjectRequest, HeadObjectResponse}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success


class CopyProxyFilesSpec extends Specification with Mockito with DocId {
  import akka.pattern.ask
  import services.FileMove.GenericMoveActor._
  import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._
  implicit val timeout:akka.util.Timeout = 30 seconds

  "CopyProxyFiles!PerformStep" should {
    "copy all referenced proxies and update the state with new locations" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[S3Client]
      val mockedCopyResult = mock[CopyObjectResponse]
      val mockedMetadata = mock[HeadObjectResponse]

      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.headObject(org.mockito.ArgumentMatchers.any[HeadObjectRequest]) returns mockedMetadata
      mockedS3Client.copyObject(org.mockito.ArgumentMatchers.any[CopyObjectRequest]) returns mockedCopyResult

      val sourceProxyList = Seq(
        ProxyLocation("source-file-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("source-file-id",None,Some("dest-file-id"),Some(sourceProxyList),None,"dest-media-bucket","dest-proxy-bucket","dest-region")

      val testMsg = PerformStep(data)

      val actor = system.actorOf(Props(new CopyProxyFiles(mockedClientMgr, Configuration.empty)))

      val result = Await.result(actor ? testMsg, 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]

      val expectedCopyRequests = Seq(1,2,3).map(n=>CopyObjectRequest.builder()
        .sourceBucket("source-proxy-bucket")
        .sourceKey(s"path/to/proxy$n")
        .destinationBucket("dest-proxy-bucket")
        .destinationKey(s"path/to/proxy$n")
        .build()
      )
      there was one(mockedS3Client).copyObject(expectedCopyRequests.head)
      there was one(mockedS3Client).copyObject(expectedCopyRequests(1))
      there was one(mockedS3Client).copyObject(expectedCopyRequests(2))

      val updatedData = result.asInstanceOf[StepSucceeded].updatedData

      updatedData.destFileProxy must beSome
      val updatedDestProxies = updatedData.destFileProxy.get
      updatedDestProxies.length mustEqual 3
      updatedDestProxies.head.bucketName mustEqual "dest-proxy-bucket"
      updatedDestProxies.head.bucketPath mustEqual "path/to/proxy1"
      updatedDestProxies.head.fileId mustEqual "dest-file-id"
      updatedDestProxies.head.proxyId mustEqual makeDocId("dest-proxy-bucket","path/to/proxy1")
      updatedDestProxies(1).bucketName mustEqual "dest-proxy-bucket"
      updatedDestProxies(1).bucketPath mustEqual "path/to/proxy2"
      updatedDestProxies(1).fileId mustEqual "dest-file-id"
      updatedDestProxies(1).proxyId mustEqual makeDocId("dest-proxy-bucket","path/to/proxy2")
      updatedDestProxies(2).bucketName mustEqual "dest-proxy-bucket"
      updatedDestProxies(2).bucketPath mustEqual "path/to/proxy3"
      updatedDestProxies(2).fileId mustEqual "dest-file-id"
      updatedDestProxies(2).proxyId mustEqual makeDocId("dest-proxy-bucket","path/to/proxy3")

      there were no(mockedS3Client).deleteObject(org.mockito.ArgumentMatchers.any[DeleteObjectRequest])
    }

    "delete all potentially existing copies (continuing if anything errors) and signal error if one copy fails" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[S3Client]
      val mockedCopyResult = mock[CopyObjectResponse]
      val mockedMetadata = mock[HeadObjectResponse]
      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.getObjectMetadata(any,any,any) returns Success(mockedMetadata)
      mockedS3Client.deleteObject(DeleteObjectRequest.builder().bucket("dest-proxy-bucket").key("path/to/proxy2").build()) throws new RuntimeException("bleagh")
      mockedS3Client.doesObjectExist(any, any) returns Success(true)
      mockedS3Client.copyObject(org.mockito.ArgumentMatchers.any[CopyObjectRequest]) returns mockedCopyResult
      mockedS3Client.copyObject(CopyObjectRequest.builder()
        .sourceBucket("source-proxy-bucket")
        .sourceKey("path/to/proxy3")
        .destinationBucket("dest-proxy-bucket")
        .destinationKey("path/to/proxy3")
        .build()
      ) throws new RuntimeException("something went ping")

      val sourceProxyList = Seq(
        ProxyLocation("source-file-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("source-file-id",None,Some("dest-file-id"),Some(sourceProxyList),None,"dest-media-bucket","dest-proxy-bucket","dest-region")

      val testMsg = PerformStep(data)

      val actor = system.actorOf(Props(new CopyProxyFiles(mockedClientMgr, Configuration.empty)))

      val result = Await.result(actor ? testMsg, 30 seconds).asInstanceOf[MoveActorMessage]
      result must beAnInstanceOf[StepFailed]

      val expectedCopyRequests = Seq(1,2,3).map(n=>CopyObjectRequest.builder()
        .sourceBucket("source-proxy-bucket")
        .sourceKey(s"path/to/proxy$n")
        .destinationBucket("dest-proxy-bucket")
        .destinationKey(s"path/to/proxy$n")
        .build()
      )
      there was one(mockedS3Client).copyObject(expectedCopyRequests.head)
      there was one(mockedS3Client).copyObject(expectedCopyRequests(1))
      there was one(mockedS3Client).copyObject(expectedCopyRequests(2))

      val expectedDeleteRequests = Seq(1,2,3).map(n=>DeleteObjectRequest.builder()
        .bucket("dest-proxy-bucket")
        .key(s"path/to/proxy$n")
        .build()
      )
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests.head)
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests(1))
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests(2))
    }
  }

  "CopyFiles!RollbackStep" should {
    "delete the copied versions of any proxies available" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[S3Client]
      val mockedCopyResult = mock[CopyObjectResponse]

      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.doesObjectExist(any, any) returns Success(true)

      val sourceProxyList = Seq(
        ProxyLocation("source-file-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("source-file-id",None,Some("dest-file-id"),Some(sourceProxyList),None,"dest-media-bucket","dest-proxy-bucket","dest-region")

      val testMsg = RollbackStep(data)

      val actor = system.actorOf(Props(new CopyProxyFiles(mockedClientMgr,Configuration.empty)))

      val result = Await.result(actor ? testMsg, 30 seconds).asInstanceOf[MoveActorMessage]

      val expectedDeleteRequests = Seq(1,2,3).map(n=>{
        DeleteObjectRequest.builder().bucket("dest-proxy-bucket").key(s"path/to/proxy$n").build()
      })

      there was one(mockedS3Client).deleteObject(expectedDeleteRequests.head)
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests(1))
      there was one(mockedS3Client).deleteObject(expectedDeleteRequests(2))
    }
  }
}
