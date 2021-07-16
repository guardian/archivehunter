package helpers

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration

import java.net.{URI, URL}
import java.time.Instant
import java.util.Date

class UserAvatarHelperSpec extends Specification with Mockito {
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
        "externalData.avatarBucket"->"test-bucket"
      ))

      val returnedUrl = new URL("https://some-presigned-url")

      val mockS3Client = mock[AmazonS3]
      mockS3Client.generatePresignedUrl(any,any,any) returns returnedUrl
      val mockS3ClientManager = mock[S3ClientManager]
      mockS3ClientManager.getClient(any) returns mockS3Client

      val toTest = new UserAvatarHelper(testConfig, mockS3ClientManager)(mock[ActorSystem], mock[Materializer])

      val expiry = Date.from(Instant.now().plusSeconds(900))
      toTest.getPresignedUrl(new URI("s3://test-bucket/someuser"), Some(expiry)) must beASuccessfulTry(returnedUrl)
      there was one(mockS3Client).generatePresignedUrl("test-bucket","someuser", expiry)
    }

    "reject the request if the bucket in the incoming url is not what we expect" in {
      val testConfig = Configuration.from(Map(
        "externalData.avatarBucket"->"another-bucket"
      ))

      val returnedUrl = new URL("https://some-presigned-url")

      val mockS3Client = mock[AmazonS3]
      mockS3Client.generatePresignedUrl(any,any,any) returns returnedUrl
      val mockS3ClientManager = mock[S3ClientManager]
      mockS3ClientManager.getClient(any) returns mockS3Client

      val toTest = new UserAvatarHelper(testConfig, mockS3ClientManager)(mock[ActorSystem], mock[Materializer])

      val expiry = Date.from(Instant.now().plusSeconds(900))
      toTest.getPresignedUrl(new URI("s3://test-bucket/someuser"), Some(expiry)) must beAFailedTry
      there was no(mockS3Client).generatePresignedUrl(any,any,any)
    }
  }
}
