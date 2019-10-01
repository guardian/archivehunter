package helpers

import java.net.URL

import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry

import scala.util.Try

object S3Helper {
  def getPresignedURL(archiveEntry:ArchiveEntry)(implicit s3Client:AmazonS3):Try[URL]= Try {
    val rq = new GeneratePresignedUrlRequest(archiveEntry.bucket, archiveEntry.path, HttpMethod.GET)
      .withResponseHeaders(new ResponseHeaderOverrides().withContentDisposition("attachment"))
    s3Client.generatePresignedUrl(rq)
  }

}
