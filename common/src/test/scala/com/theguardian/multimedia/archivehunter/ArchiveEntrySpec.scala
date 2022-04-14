package com.theguardian.multimedia.archivehunter
import java.time.{Instant, LocalDateTime, ZoneId, ZoneOffset, ZonedDateTime}
import java.util.Date
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.sksamuel.elastic4s.http.search.SearchHit
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, MimeType, StorageClass, StorageClassEncoder, ZonedDateTimeEncoder}
import org.elasticsearch.index.reindex.ScrollableHitSource.BasicHit
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, HeadObjectResponse}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ArchiveEntrySpec extends Specification with Mockito with ArchiveEntryHitReader with ZonedDateTimeEncoder with StorageClassEncoder {
  "ArchiveEntry" should {
    "be serializable via Circe" in {
      import io.circe.syntax._
      import io.circe.generic.auto._

      val test = ArchiveEntry("some-id",
      "bucket-name",
      "path/to/content.file",
        Some("abcdefg"),
        Some("eu-west-1"),
        Some(".file"),
        123456L,
        ZonedDateTime.now(),
      "some-etag",
        MimeType("application","octet-stream"),
        true,
        StorageClass.STANDARD,
        Seq(),
        false,
        None)

      val result = test.asJson
      (result \\ "maybeVersion").head.as[String] must beRight("abcdefg")
    }

    "be encodable via Circe" in {
      import io.circe.syntax._
      import io.circe.generic.auto._

      val testData = """{
                       |    "id": "YXJjaGl2ZWh1bnRlci10ZXN0LW1lZGlhOjIwMjIwMzIyXzE0NTE0My5tcDQ=",
                       |    "bucket": "archivehunter-test-media",
                       |    "path": "20220322_145143.mp4",
                       |    "maybeVersion": "RxfqvFA6_xXPN.PILLCfuSuPn59fnvzu",
                       |    "region": "eu-west-1",
                       |    "file_extension": "mp4",
                       |    "size": 251588972,
                       |    "last_modified": "2022-04-14T15:09:38Z",
                       |    "etag": "\"7bbefd9def4ff0f1792819c35bf5ba23-15\"",
                       |    "mimeType": {
                       |        "major": "video",
                       |        "minor": "mp4"
                       |    },
                       |    "proxied": false,
                       |    "storageClass": "STANDARD",
                       |    "lightboxEntries": [],
                       |    "beenDeleted": false,
                       |    "mediaMetadata": null
                       |}""".stripMargin
      val record = io.circe.parser.parse(testData).flatMap(_.as[ArchiveEntry])
      record must beRight
      record.right.get.maybeVersion must beSome("RxfqvFA6_xXPN.PILLCfuSuPn59fnvzu")
    }
  }
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
