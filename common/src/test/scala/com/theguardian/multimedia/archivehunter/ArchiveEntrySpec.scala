package com.theguardian.multimedia.archivehunter
import java.time.{ZoneId, ZonedDateTime}
import java.util.Date

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ArchiveEntrySpec extends Specification with Mockito {
  "ArchiveEntry.fromS3" should {
    "look up metadata in S3 and populate an ArchiveEntry object" in {
      val mockMetadata = mock[ObjectMetadata]
      mockMetadata.getETag.returns("test-etag")
      mockMetadata.getContentLength.returns(123456L)
      mockMetadata.getLastModified.returns(new Date(118,0,1,23,21,0))

      implicit val mockClient = mock[AmazonS3Client]
      mockClient.getObjectMetadata("test-bucket","test/path/to/file.ext").returns(mockMetadata)

      val newEntry = Await.result(ArchiveEntry.fromS3("test-bucket","test/path/to/file.ext"), 5 seconds)
      newEntry must beSuccessfulTry(ArchiveEntry(ArchiveEntry.makeDocId("test-bucket","test/path/to/file.ext"),"test-bucket","test/path/to/file.ext",Some("ext"),123456L,ZonedDateTime.of(2018,1,1,23,21,0,0,ZoneId.systemDefault()),"test-etag",false))
    }

    "return any exception in the AWS SDK as a failed Try" in {
      val mockMetadata = mock[ObjectMetadata]
      mockMetadata.getETag.returns("test-etag")
      mockMetadata.getContentLength.returns(123456L)
      mockMetadata.getLastModified.returns(new Date(118,0,1,23,21,0))

      implicit val mockClient = mock[AmazonS3Client]
      mockClient.getObjectMetadata("test-bucket","test/path/to/file.ext").throws(new RuntimeException("kaboom"))

      val newEntry = Await.result(ArchiveEntry.fromS3("test-bucket","test/path/to/file.ext"), 5 seconds)
      newEntry must beFailedTry
    }
  }
}
