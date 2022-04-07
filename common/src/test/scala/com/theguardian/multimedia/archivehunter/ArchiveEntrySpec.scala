package com.theguardian.multimedia.archivehunter
import java.time.{Instant, LocalDateTime, ZoneId, ZoneOffset, ZonedDateTime}
import java.util.Date
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, MimeType, StorageClass}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, HeadObjectResponse}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ArchiveEntrySpec extends Specification with Mockito {
  "ArchiveEntry.fromS3" should {
    "look up metadata in S3 and populate an ArchiveEntry object" in {
      val mockMetadata = HeadObjectResponse.builder()
        .eTag("test-etag")
        .contentLength(123456L)
        .lastModified(LocalDateTime.of(2018,1,1,23,21,0).toInstant(ZoneOffset.ofHours(0)))
        .build()

      implicit val mockClient = mock[S3Client]
      mockClient.headObject(org.mockito.ArgumentMatchers.any[HeadObjectRequest]).returns(mockMetadata)

      val newEntry = Await.result(ArchiveEntry.fromS3("test-bucket","test/path/to/file.ext", Some("abcde"), "eu-west-1"), 5.seconds)

      newEntry mustEqual ArchiveEntry(ArchiveEntry.makeDocId("test-bucket","test/path/to/file.ext"),
        "test-bucket","test/path/to/file.ext", Some("abcde"),
        Some("eu-west-1"), Some("ext"),123456L,
        ZonedDateTime.of(2018,1,1,23,21,0,0,ZoneId.systemDefault()),
        "test-etag", MimeType("application","octet-stream") ,false, StorageClass.STANDARD, Seq(),false,None)
    }

    "return any exception in the AWS SDK as a failed Try" in {
      val mockMetadata = HeadObjectResponse.builder()
        .eTag("test-etag")
        .contentLength(123456L)
        .lastModified(LocalDateTime.of(2018,1,1,23,21,0).toInstant(ZoneOffset.ofHours(0)))

      implicit val mockClient = mock[S3Client]
      mockClient.headObject(org.mockito.ArgumentMatchers.any[HeadObjectRequest]).throws(new RuntimeException("kaboom"))

      Await.result(ArchiveEntry.fromS3("test-bucket","test/path/to/file.ext", None, "eu-west-1"), 5.seconds) must throwA[RuntimeException]

    }
  }
}
