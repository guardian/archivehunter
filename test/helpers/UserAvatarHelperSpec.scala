package helpers

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.specs2.mock.Mockito
import org.specs2.mock.mockito.MockitoMatchers
import org.specs2.mutable.Specification
import play.api.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

import java.net.{URI, URL}
import java.time.Instant
import java.util.Date
import scala.util.Success

class UserAvatarHelperSpec extends Specification with Mockito {
  import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._

  "UserAvatarHelper.getAvatarLocation" should {
    "form an s3 url to the given user picture" in {
      val testConfig = Configuration.from(Map(
        "externalData.avatarBucket"->"test-bucket"
      ))

      val toTest = new UserAvatarHelper(testConfig, mock[S3ClientManager])(mock[ActorSystem], mock[Materializer])

      toTest.getAvatarLocation("someuser").map(_.toString) must beSome("s3://test-bucket/someuser")
    }
  }

  "UserAvatarHelper.getPresignedURL" should {
    "call out to S3 to get a presigned URL for the incoming S3 URI" in {
      val testConfig = Configuration.from(Map(
        "externalData.avatarBucket"->"test-bucket",
        "externalData.awsRegion"->"ap-east-1"
      ))

      val mockS3ClientManager = mock[S3ClientManager]

      val toTest = new UserAvatarHelper(testConfig, mockS3ClientManager)(mock[ActorSystem], mock[Materializer])

      val result = toTest.getPresignedUrl(new URI("s3://test-bucket/someuser"), Some(900))
      result must beASuccessfulTry
      result.get.getHost mustEqual "test-bucket.s3.ap-east-1.amazonaws.com"
      result.get.getPath mustEqual "/someuser"
      val queryParts = result.get
        .getQuery
        .stripPrefix("?")
        .split("&")
        .map(elem=>{
          val parts = elem.split("=")
          (parts.head, if(parts.length>1) Some(parts(1)) else None)
        })
        .toMap

      queryParts.get("X-Amz-Algorithm").flatten must beSome
      queryParts.get("X-Amz-Date").flatten must beSome
      queryParts.get("X-Amz-SignedHeaders").flatten must beSome
      queryParts.get("X-Amz-Expires").flatten must beSome
      queryParts.get("X-Amz-Credential").flatten must beSome
      queryParts.get("X-Amz-Signature").flatten must beSome
    }

    "reject the request if the bucket in the incoming url is not what we expect" in {
      val testConfig = Configuration.from(Map(
        "externalData.avatarBucket"->"another-bucket"
      ))

      val mockS3ClientManager = mock[S3ClientManager]
      val toTest = new UserAvatarHelper(testConfig, mockS3ClientManager)(mock[ActorSystem], mock[Materializer])

      toTest.getPresignedUrl(new URI("s3://test-bucket/someuser"), Some(900)) must beAFailedTry
    }
  }
}
