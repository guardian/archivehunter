package helpers

import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class S3ClientManager @Inject() (config:Configuration) {
  def getS3Client:AmazonS3 = AmazonS3ClientBuilder.defaultClient()
}
