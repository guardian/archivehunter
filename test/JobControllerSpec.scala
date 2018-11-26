import java.time.ZonedDateTime

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common._
import controllers.JobController
import cmn_models._
import models._
import org.specs2.mock.Mockito
import org.specs2.mock.mockito.MockitoMatchers
import org.specs2.mutable.Specification
import play.api.Configuration
import play.api.mvc.ControllerComponents

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class JobControllerSpec extends Specification with Mockito with MockitoMatchers {
  sequential

  "JobController.thumbnailOrOriginalMedia" should {
    "return a failure if passed proxy or thumbnail media" in new AkkaTestkitSpecs2Support{
      val mockConfig = mock[Configuration]
      val mockCC = mock[ControllerComponents]
      val mockJobModelDAO = mock[JobModelDAO]
      val proxyLocationDAO = mock[ProxyLocationDAO]
      val mockIndexer = mock[Indexer]
      val mockesClientManager = mock[ESClientManager]
      val mocks3ClientManager = mock[S3ClientManager]
      val mockddbClientManager = mock[DynamoClientManager]

      mockConfig.getOptional[String](anyString)(any).returns(None)
      mockConfig.get[String](anyString)(any).returns("proxiesTable")

      implicit val mockEsClient = mock[HttpClient]
      val c = new JobController(mockConfig,mockCC,mockJobModelDAO,mockesClientManager, mocks3ClientManager, mockddbClientManager)

      val toTest1 = JobModel("test","test",None,None,JobStatus.ST_PENDING,None,"fake-source-id",SourceType.SRC_PROXY)
      val result1 = Await.result(c.thumbnailJobOriginalMedia(toTest1),10 seconds)
      result1 must beLeft

      val toTest2 = JobModel("test","test",None,None,JobStatus.ST_PENDING,None,"fake-source-id",SourceType.SRC_THUMBNAIL)
      val result2 = Await.result(c.thumbnailJobOriginalMedia(toTest2),10 seconds)
      result2 must beLeft
    }

    "perform an index lookup if passed media type" in new AkkaTestkitSpecs2Support{
      val mockConfig = mock[Configuration]
      val mockCC = mock[ControllerComponents]
      val mockJobModelDAO = mock[JobModelDAO]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockIndexer = mock[Indexer]
      val mockesClientManager = mock[ESClientManager]
      val mocks3ClientManager = mock[S3ClientManager]
      val mockddbClientManager = mock[DynamoClientManager]

      mockConfig.getOptional[String](anyString)(any).returns(None)
      mockConfig.get[String](anyString)(any).returns("proxiesTable")

      implicit val mockEsClient = mock[HttpClient]
      val c = new JobController(mockConfig,mockCC,mockJobModelDAO,mockesClientManager, mocks3ClientManager, mockddbClientManager){
        override protected val indexer = mockIndexer
        override protected val proxyLocationDAO = mockProxyLocationDAO
      }

      val toTest = JobModel("test","test",None,None,JobStatus.ST_PENDING,None,"fake-source-id",SourceType.SRC_MEDIA)
      val mockResponse = ArchiveEntry("fake-id","fake-bucket","fake-path",None,1234L,ZonedDateTime.now(),"fake-etag",MimeType("video","something"),false,StorageClass.STANDARD)

      mockIndexer.getById("fake-source-id")(mockEsClient).returns(Future(mockResponse))

      val result = Await.result(c.thumbnailJobOriginalMedia(toTest),10 seconds)
      result must beRight(mockResponse)
    }
  }

  "JobController.updateProxyRef" should {
    "create a ProxyLocation object based on the input parameters" in new AkkaTestkitSpecs2Support {
      val mockConfig = mock[Configuration]
      val mockCC = mock[ControllerComponents]
      val mockJobModelDAO = mock[JobModelDAO]
      val mockProxyLocationDAO = mock[ProxyLocationDAO]
      val mockIndexer = mock[Indexer]
      val mockesClientManager = mock[ESClientManager]
      val mocks3ClientManager = mock[S3ClientManager]
      val mockddbClientManager = mock[DynamoClientManager]

      mockConfig.getOptional[String](anyString)(any).returns(None)
      mockConfig.get[String](anyString)(any).returns("proxiesTable")

      implicit val mockS3Client = mock[AmazonS3]
      implicit val mockDynamoClient = mock[DynamoClient]
      val fakeMd = new ObjectMetadata()
      fakeMd.setContentType("image/jpeg")

      mockS3Client.getObjectMetadata("proxybucket","path/to/output.img").returns(fakeMd)

      mockProxyLocationDAO.saveProxy(any)(any).returns(Future(None))
      val c = new JobController(mockConfig,mockCC,mockJobModelDAO,mockesClientManager, mocks3ClientManager, mockddbClientManager){
        override protected val indexer = mockIndexer
        override protected val proxyLocationDAO = mockProxyLocationDAO
      }

      val report = JobReportSuccess("ok","s3://proxybucket/path/to/output.img","s3://sourcebucket/path/to/source.mp4")
      val sourceEntry = ArchiveEntry("fake-id","fake-bucket","fake-path",None,1234L,ZonedDateTime.now(),"fake-etag",MimeType("video","something"),false,StorageClass.STANDARD)

      val result = Await.result(c.updateProxyRef(report,sourceEntry), 10 seconds)
      result must beRight
    }

    "pass through an error returned from ProxyLocation.fromS3" in new AkkaTestkitSpecs2Support {
      val mockConfig = mock[Configuration]
      val mockCC = mock[ControllerComponents]
      val mockJobModelDAO = mock[JobModelDAO]
      val proxyLocationDAO = mock[ProxyLocationDAO]
      val mockIndexer = mock[Indexer]
      val mockesClientManager = mock[ESClientManager]
      val mocks3ClientManager = mock[S3ClientManager]
      val mockddbClientManager = mock[DynamoClientManager]

      mockConfig.getOptional[String](anyString)(any).returns(None)
      mockConfig.get[String](anyString)(any).returns("proxiesTable")

      implicit val mockS3Client = mock[AmazonS3]
      implicit val mockDynamoClient = mock[DynamoClient]
      val fakeMd = new ObjectMetadata()
      fakeMd.setContentType("image/jpeg")

      mockS3Client.getObjectMetadata("proxybucket","/path/to/output.img").throws(new RuntimeException("test exception"))

      proxyLocationDAO.saveProxy(any)(any).returns(Future(None))
      val c = new JobController(mockConfig,mockCC,mockJobModelDAO,mockesClientManager, mocks3ClientManager, mockddbClientManager)

      val report = JobReportSuccess("ok","s3c://proxybucket/path/to/output.img","s3://sourcebucket/path/to/source.mp4")
      val sourceEntry = ArchiveEntry("fake-id","fake-bucket","fake-path",None,1234L,ZonedDateTime.now(),"fake-etag",MimeType("video","something"),false,StorageClass.STANDARD)

      val result = Await.result(c.updateProxyRef(report,sourceEntry), 10 seconds)
      result must beLeft
    }
  }
}
