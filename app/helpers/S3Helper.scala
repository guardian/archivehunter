package helpers

import java.net.URL
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

import scala.util.Try

object S3Helper {
  /**
    * Generates a presigned URL to read the given object
    * @param archiveEntry  archive entry that we want to read
    * @param s3Client implicitly provided S3 client
    * @return a Try, containing either the presigned URL or an error
    */
  def getPresignedURL(archiveEntry:ArchiveEntry)(implicit s3Client:S3Client):Try[URL]= Try {
    val presigner = archiveEntry.region match {
      case Some(rgn)=>S3Presigner.builder().region(Region.of(rgn)).build()
      case None=>S3Presigner.create()
    }

    val initial = GetObjectRequest.builder().bucket(archiveEntry.bucket).key(archiveEntry.path)
    val finalReq = archiveEntry.maybeVersion match {
      case Some(ver)=>initial.versionId(ver)
      case None=>initial
    }
    presigner
      .presignGetObject(GetObjectPresignRequest.builder().getObjectRequest(finalReq.build()).build())
      .url()
  }

  /**
    * Generates a presigned URL to read the given object
    * @param proxyLocation  proxy entry that we want to read
    * @param s3Client implicitly provided S3 client
    * @return a Try, containing either the presigned URL or an error
    */
  def getPresignedURL(proxyLocation:ProxyLocation)(implicit s3Client:S3Client):Try[URL] = Try {
    val presigner = proxyLocation.region match {
      case Some(rgn)=>S3Presigner.builder().region(Region.of(rgn)).build()
      case None=>S3Presigner.create()
    }
    val req = GetObjectRequest.builder().bucket(proxyLocation.bucketName).key(proxyLocation.bucketPath).build()
    presigner
      .presignGetObject(GetObjectPresignRequest.builder().getObjectRequest(req).build())
      .url()
  }
}
