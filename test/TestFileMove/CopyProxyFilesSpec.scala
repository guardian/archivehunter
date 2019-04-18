package TestFileMove

import akka.actor.Props
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.CopyObjectResult
import com.theguardian.multimedia.archivehunter.common.{DocId, ProxyLocation, ProxyType, StorageClass}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.FileMove.CopyProxyFiles

import scala.concurrent.Await
import scala.concurrent.duration._


class CopyProxyFilesSpec extends Specification with Mockito with DocId {
  import akka.pattern.ask
  import services.FileMove.GenericMoveActor._

  implicit val timeout:akka.util.Timeout = 30 seconds

  "CopyProxyFiles!PerformStep" should {
    "copy all referenced proxies and update the state with new locations" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      val mockedCopyResult = mock[CopyObjectResult]

      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.copyObject(any, any, any, any) returns mockedCopyResult

      val sourceProxyList = Seq(
        ProxyLocation("source-file-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("source-file-id",None,Some("dest-file-id"),Some(sourceProxyList),None,"dest-media-bucket","dest-proxy-bucket")

      val testMsg = PerformStep(data)

      val actor = system.actorOf(Props(new CopyProxyFiles(mockedClientMgr)))

      val result = Await.result(actor ? testMsg, 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]

      there was one(mockedS3Client).copyObject("source-proxy-bucket","path/to/proxy1","dest-proxy-bucket","path/to/proxy1")
      there was one(mockedS3Client).copyObject("source-proxy-bucket","path/to/proxy2","dest-proxy-bucket","path/to/proxy2")
      there was one(mockedS3Client).copyObject("source-proxy-bucket","path/to/proxy3","dest-proxy-bucket","path/to/proxy3")

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

      there were no(mockedS3Client).deleteObject(any,any)
    }

    "delete all potentially existing copies (continuing if anything errors) and signal error if one copy fails" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      val mockedCopyResult = mock[CopyObjectResult]

      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.deleteObject("dest-proxy-bucket","path/to/proxy2") throws new RuntimeException("bleagh")
      mockedS3Client.doesObjectExist(any, any) returns true
      mockedS3Client.copyObject(any, any, any, any) returns mockedCopyResult
      mockedS3Client.copyObject("source-proxy-bucket","path/to/proxy3","dest-proxy-bucket","path/to/proxy3") throws new RuntimeException("something went ping")

      val sourceProxyList = Seq(
        ProxyLocation("source-file-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("source-file-id",None,Some("dest-file-id"),Some(sourceProxyList),None,"dest-media-bucket","dest-proxy-bucket")

      val testMsg = PerformStep(data)

      val actor = system.actorOf(Props(new CopyProxyFiles(mockedClientMgr)))

      val result = Await.result(actor ? testMsg, 30 seconds).asInstanceOf[MoveActorMessage]
      result must beAnInstanceOf[StepFailed]

      there was one(mockedS3Client).copyObject("source-proxy-bucket","path/to/proxy1","dest-proxy-bucket","path/to/proxy1")
      there was one(mockedS3Client).copyObject("source-proxy-bucket","path/to/proxy2","dest-proxy-bucket","path/to/proxy2")
      there was one(mockedS3Client).copyObject("source-proxy-bucket","path/to/proxy3","dest-proxy-bucket","path/to/proxy3")

      there was one(mockedS3Client).deleteObject("dest-proxy-bucket","path/to/proxy1")
      there was one(mockedS3Client).deleteObject("dest-proxy-bucket","path/to/proxy2")
      there was one(mockedS3Client).deleteObject("dest-proxy-bucket","path/to/proxy3")
    }
  }

  "CopyFiles!RollbackStep" should {
    "delete the copied versions of any proxies available" in new AkkaTestkitSpecs2Support {
      val mockedClientMgr = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      val mockedCopyResult = mock[CopyObjectResult]

      mockedClientMgr.getS3Client(any,any) returns mockedS3Client
      mockedS3Client.doesObjectExist(any, any) returns true

      val sourceProxyList = Seq(
        ProxyLocation("source-file-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("source-file-id",None,Some("dest-file-id"),Some(sourceProxyList),None,"dest-media-bucket","dest-proxy-bucket")

      val testMsg = RollbackStep(data)

      val actor = system.actorOf(Props(new CopyProxyFiles(mockedClientMgr)))

      val result = Await.result(actor ? testMsg, 30 seconds).asInstanceOf[MoveActorMessage]

      there was one(mockedS3Client).deleteObject("dest-proxy-bucket","path/to/proxy1")
      there was one(mockedS3Client).deleteObject("dest-proxy-bucket","path/to/proxy2")
      there was one(mockedS3Client).deleteObject("dest-proxy-bucket","path/to/proxy3")
    }
  }
}
