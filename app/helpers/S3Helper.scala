package helpers

import java.net.URL
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

import scala.util.Try

object S3Helper {
  import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._
  /**
    * Generates a presigned URL to read the given object
    * @param archiveEntry  archive entry that we want to read
    * @param s3Client implicitly provided S3 client
    * @return a Try, containing either the presigned URL or an error
    */
  def getPresignedURL(archiveEntry:ArchiveEntry, expireInSeconds:Int, defaultRegion:Region, profileName:Option[String])(implicit s3ClientMgr:S3ClientManager):Try[URL] = {
    val s3Client = s3ClientMgr.getS3Client(profileName)
    val credentialProvider = s3ClientMgr.newCredentialsProvider(profileName)
    s3Client.generatePresignedUrl(archiveEntry.bucket,
      archiveEntry.path,
      expireInSeconds,
      archiveEntry.region.map(Region.of).getOrElse(defaultRegion),
      archiveEntry.maybeVersion,
      credentialProvider
    )
  }


  /**
    * Generates a presigned URL to read the given object
    * @param proxyLocation  proxy entry that we want to read
    * @param s3Client implicitly provided S3 client
    * @return a Try, containing either the presigned URL or an error
    */
  def getPresignedURL(proxyLocation:ProxyLocation, expireInSeconds:Int, defaultRegion:Region, profileName:Option[String])(implicit s3ClientMgr:S3ClientManager):Try[URL] = {
    val region = proxyLocation.region.map(Region.of).getOrElse(defaultRegion)
    val s3Client = s3ClientMgr.getS3Client(profileName, Some(region))
    val credentialProvider = s3ClientMgr.newCredentialsProvider(profileName)
    s3Client.generatePresignedUrl(proxyLocation.bucketName, proxyLocation.bucketPath, expireInSeconds, region, None, credentialProvider)
  }
}
