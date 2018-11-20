import java.time.Instant

import akka.stream.alpakka.s3.scaladsl.ListBucketResultContents
import helpers.{S3Error, S3XMLProcessor}
import org.specs2.mock.Mockito
import org.specs2.mutable._
import org.mockito.Mockito.{times, verify}

import scala.io.Source
import scala.xml.pull.XMLEventReader

class S3XMLProcessorSpec extends Specification with Mockito {
  "S3XMLProcessor.parseDoc" should {
    "handle parsing a sample XML document and call the callback with ListBucketResultContents" in {
      val sampleDoc = """<?xml version="1.0" encoding="UTF-8"?>
                        |<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        |    <Name>bucket</Name>
                        |    <Prefix/>
                        |    <KeyCount>205</KeyCount>
                        |    <MaxKeys>1000</MaxKeys>
                        |    <IsTruncated>false</IsTruncated>
                        |    <Contents>
                        |        <Key>my-image.jpg</Key>
                        |        <LastModified>2009-10-12T17:50:30.000Z</LastModified>
                        |        <ETag>&quot;fba9dede5f27731c9771645a39863328&quot;</ETag>
                        |        <Size>434234</Size>
                        |        <StorageClass>STANDARD</StorageClass>
                        |    </Contents>
                        |</ListBucketResult>""".stripMargin

      var resultsCollector:Seq[ListBucketResultContents] = Seq()
      val xml = new XMLEventReader(Source.fromString(sampleDoc))
      val test = new S3XMLProcessor()

      val mockCB = mock[Function1[Either[S3Error, ListBucketResultContents],Unit]]
      val result = test.parseDoc(xml)(mockCB)

      val expectedResult = ListBucketResultContents(
                  "bucket",
                  "my-image.jpg",
                  "fba9dede5f27731c9771645a39863328",
                  434234L,
                  Instant.parse("2009-10-12T17:50:30.000Z"),
                  "STANDARD"
                )

      verify(mockCB, times(1)).apply(Right(expectedResult))
      1 mustEqual 1
    }
  }
}
