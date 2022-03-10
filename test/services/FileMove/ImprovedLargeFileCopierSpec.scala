package services.FileMove

import TestFileMove.AkkaTestkitSpecs2Support
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.amazonaws.regions.Regions
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.FileMove.ImprovedLargeFileCopier.{CompletedUpload, HeadInfo, UploadPart, UploadedPart}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class ImprovedLargeFileCopierSpec extends Specification with Mockito {
  sequential

  "ImprovedLargeFileCopier.makeS3Uri" should {
    "url-encode paths" in {
      implicit val actorSystem:ActorSystem = mock[ActorSystem]
      implicit val mat:Materializer = mock[Materializer]

      val toTest = new ImprovedLargeFileCopier()
      toTest.makeS3Uri(Regions.EU_WEST_1, "somebucket","path to a/very long/file.mxf", Some("vvvvvv")) mustEqual "https://s3.eu-west-1.amazonaws.com/somebucket/path+to+a/very+long/file.mxf?versionId=vvvvvv"
    }

    "handle non-ascii characters" in {
      implicit val actorSystem:ActorSystem = mock[ActorSystem]
      implicit val mat:Materializer = mock[Materializer]

      val toTest = new ImprovedLargeFileCopier()
      toTest.makeS3Uri(Regions.EU_WEST_1, "somebucket","path to a/arsène wenger est alleé en vacances.mxf", None) mustEqual "https://s3.eu-west-1.amazonaws.com/somebucket/path+to+a/ars%25C3%25A8ne+wenger+est+alle%25C3%25A9+en+vacances.mxf"
    }
  }
  "ImprovedLargeFileCopier.headSourceFile" should {
    "return file metadata about a known public file" in new AkkaTestkitSpecs2Support {
      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = toTest.newPoolClientFlow[Any](Regions.EU_WEST_1)
      val result = Await.result(
        toTest.headSourceFile(Regions.EU_WEST_1,None, "gnm-multimedia-cdn","interactive/speedtest/testmpfile.dat",None),
        10.seconds
      )

     result must beSome(
       ImprovedLargeFileCopier.HeadInfo(
        "gnm-multimedia-cdn",
         "interactive/speedtest/testmpfile.dat",
         None,
         "Thu, 25 Apr 2019 09:39:03 GMT",
         10485760,
         Some("\"ee32e01c6f0941f94330fc994dc6f31d-2\""),
         "binary/octet-stream",
         None,
         None
       )
     )
    }
  }

  /**
    * takes a list of headers and returns a map of them, keyed by header name
    * @param headers sequence of headers
    * @return
    */
  def mapOfHeaders(headers:Seq[HttpHeader]):Map[String, HttpHeader] = {
    headers.foldLeft(Map[String, HttpHeader]())((m, h)=>m + (h.name()->h))
  }

  "ImprovedLargeFileCopier.abortMultipartUpload" should {
    "send the abort request to S3" in new AkkaTestkitSpecs2Support {
      var lastRequest:HttpRequest = _

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        lastRequest = req
        Success(HttpResponse(StatusCodes.OK))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[Any](validator)

      val result = Await.result(
        toTest
          .abortMultipartUpload(Regions.EU_WEST_1, None, "source","/path/to/source","abcde")(pool.asInstanceOf[toTest.HostConnectionPool[Any]]),
        5.seconds
      )

      lastRequest.uri.toString() mustEqual "https://s3.eu-west-1.amazonaws.com/source/path/to/source?uploadId=abcde"
      lastRequest.method mustEqual HttpMethods.DELETE
    }

    "fail the returned future if a server error occurs" in new AkkaTestkitSpecs2Support {
      var lastRequest:HttpRequest = _

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        lastRequest = req
        Success(HttpResponse(StatusCodes.InternalServerError))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[Any](validator)

      val result = Try {
        Await.result(
          toTest
            .abortMultipartUpload(Regions.EU_WEST_1, None, "source", "/path/to/source", "abcde")(pool.asInstanceOf[toTest.HostConnectionPool[Any]]),
          5.seconds
        )
      }

      lastRequest.uri.toString() mustEqual "https://s3.eu-west-1.amazonaws.com/source/path/to/source?uploadId=abcde"
      lastRequest.method mustEqual HttpMethods.DELETE
      result must beAFailedTry
    }

    "fail the returned future if a akka-http aborts" in new AkkaTestkitSpecs2Support {
      var lastRequest:HttpRequest = _

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        lastRequest = req
        Failure(new RuntimeException("something went wrong"))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[Any](validator)

      val result = Try {
        Await.result(
          toTest
            .abortMultipartUpload(Regions.EU_WEST_1, None, "source", "/path/to/source", "abcde")(pool.asInstanceOf[toTest.HostConnectionPool[Any]]),
          5.seconds
        )
      }

      lastRequest.uri.toString() mustEqual "https://s3.eu-west-1.amazonaws.com/source/path/to/source?uploadId=abcde"
      lastRequest.method mustEqual HttpMethods.DELETE
      result must beAFailedTry
    }
  }

  "ImprovedLargeFileCopier.initiateMultipartUpload" should {
    "send the initiate request to S3" in new AkkaTestkitSpecs2Support {
      var lastRequest:HttpRequest = _

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        val xmlReply=
          """<?xml version="1.0" encoding="UTF-8"?>
            |<InitiateMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Bucket>example-bucket</Bucket>
              <Key>example-object</Key>
              <UploadId>VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5tMnRzIHVwbG9hZA</UploadId>
            </InitiateMultipartUploadResult>""".stripMargin

        lastRequest = req
        Success(HttpResponse(StatusCodes.OK, entity = HttpEntity(xmlReply)))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[Any](validator)

      val fakeMeta = HeadInfo("some-bucket","path/to/some/content",None, "last-mod-time",123456L, Some("etag"), "binary/octet-stream", None, None)

      val result = Await.result(
        toTest
          .initiateMultipartUpload(Regions.EU_WEST_1, None, "source","/path/to/source",fakeMeta),
        5.seconds
      )

      val headers = mapOfHeaders(lastRequest.headers)
      lastRequest.uri.toString() mustEqual "https://s3.eu-west-1.amazonaws.com/source/path/to/source?uploads"
      lastRequest.method mustEqual HttpMethods.POST
      lastRequest.entity.contentType.toString() mustEqual "binary/octet-stream"
      headers.get("x-amz-acl").map(_.value()) must beSome("private")
    }

    "fail the returned future if a server error occurs" in new AkkaTestkitSpecs2Support {
      var lastRequest:HttpRequest = _

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        lastRequest = req
        Success(HttpResponse(StatusCodes.InternalServerError))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[Any](validator)
      val fakeMeta = HeadInfo("some-bucket","path/to/some/content",None, "last-mod-time",123456L, Some("etag"), "binary/octet-stream", None, None)

      val result = Try {
        Await.result(
          toTest
            .initiateMultipartUpload(Regions.EU_WEST_1, None, "source","/path/to/source",fakeMeta),
          5.seconds
        )
      }

      val headers = mapOfHeaders(lastRequest.headers)
      lastRequest.uri.toString() mustEqual "https://s3.eu-west-1.amazonaws.com/source/path/to/source?uploads"
      lastRequest.method mustEqual HttpMethods.POST
      lastRequest.entity.contentType.toString() mustEqual "binary/octet-stream"
      headers.get("x-amz-acl").map(_.value()) must beSome("private")
      result must beAFailedTry
    }

    "fail the returned future if a akka-http aborts" in new AkkaTestkitSpecs2Support {
      var lastRequest:HttpRequest = _

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        lastRequest = req
        Failure(new RuntimeException("something went wrong"))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[Any](validator)
      val fakeMeta = HeadInfo("some-bucket","path/to/some/content",None, "last-mod-time",123456L, Some("etag"), "binary/octet-stream", None, None)

      val result = Try {
        Await.result(
          toTest
            .initiateMultipartUpload(Regions.EU_WEST_1, None, "source","/path/to/source",fakeMeta),
          5.seconds
        )
      }

      val headers = mapOfHeaders(lastRequest.headers)
      lastRequest.uri.toString() mustEqual "https://s3.eu-west-1.amazonaws.com/source/path/to/source?uploads"
      lastRequest.method mustEqual HttpMethods.POST
      lastRequest.entity.contentType.toString() mustEqual "binary/octet-stream"
      headers.get("x-amz-acl").map(_.value()) must beSome("private")
      result must beAFailedTry
    }
  }

  "ImprovedLargeFileCopier.deriveParts" should {
    "return a list of UploadPart" in {
      val parts = ImprovedLargeFileCopier.deriveParts("destbucket","path/to/dest",
        HeadInfo("sourcebucket","path/to/file",None,"last-modtime",12345678L, None, "binary/octet-stream", None, None)
      )

      parts.length mustEqual 2
      parts.head.start mustEqual 0
      parts.head.end mustEqual 10485759
      parts.head.partNumber mustEqual 1
      parts.head.key mustEqual "path/to/dest"
      parts.head.bucket mustEqual "destbucket"

      parts(1).start mustEqual 10485760
      parts(1).end mustEqual 12345677
      parts(1).partNumber mustEqual 2
      parts(1).key mustEqual "path/to/dest"
      parts(1).bucket mustEqual "destbucket"
    }
  }

  "ImprovedLargeFileCopier.estimatePartSize" should {
    "allow forcing of a certain number of parts" in {
      LargeFileCopier.estimatePartSize(5368709120L, None) mustEqual 10485760  //512 parts
      LargeFileCopier.estimatePartSize(5368709120L, Some(256)) mustEqual 20971520
    }
  }
  "ImprovedLargeFileCopier.sendPartCopies" should {
    "send an upload-part-copy request for every given chunk" in new AkkaTestkitSpecs2Support {
      val requests = scala.collection.mutable.ListBuffer[HttpRequest]()

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        val partNumber = req.uri.query().get("partNumber").getOrElse("XXX")

        val responseContent = s"""<?xml version="1.0" encoding="UTF-8"?>
                                |<CopyPartResult>
                                |   <ETag>etag-$partNumber</ETag>
                                |   <LastModified>another-timestamp</LastModified>
                                |</CopyPartResult>""".stripMargin
        requests.append(req)
        Success(HttpResponse(StatusCodes.OK, entity = HttpEntity(responseContent)))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[UploadPart](validator)
      val fakeMeta = HeadInfo("some-bucket","path/to/some/content",None, "last-mod-time",123456L, Some("etag"), "binary/octet-stream", None, None)
      val partsList = Seq(
        UploadPart("destbucket","path/to/dest", 0L, 10*1024*1024L-1, 1),
        UploadPart("destbucket","path/to/dest", 10*1024*1024L, 20*1024*1024L-1, 2),
        UploadPart("destbucket","path/to/dest", 20*1024*1024L, 22*1024*1024L, 3),
      )
      val result = Await.result(
        toTest.sendPartCopies(Regions.EU_WEST_1,None,"some-upload-id", partsList, fakeMeta),
        10.seconds
      )

      val sortedResult = result.sortBy(_.partNumber)
      sortedResult.length mustEqual 3
      sortedResult.head.partNumber mustEqual 1
      sortedResult.head.uploadedETag mustEqual "etag-1"
      sortedResult.head.uploadId mustEqual "some-upload-id"
      sortedResult(1).partNumber mustEqual 2
      sortedResult(1).uploadedETag mustEqual "etag-2"
      sortedResult(1).uploadId mustEqual "some-upload-id"
      sortedResult(2).partNumber mustEqual 3
      sortedResult(2).uploadedETag mustEqual "etag-3"
      sortedResult(2).uploadId mustEqual "some-upload-id"

      val requestHeaders = requests.map(rq=>mapOfHeaders(rq.headers))
      requests.length mustEqual 3
      requests.head.uri.toString mustEqual "https://s3.eu-west-1.amazonaws.com/destbucket/path/to/dest?partNumber=1&uploadId=some-upload-id"
      requests.head.method mustEqual HttpMethods.PUT
      requestHeaders.head.get("x-amz-copy-source").map(_.value()) must beSome("some-bucket%2Fpath%2Fto%2Fsome%2Fcontent")
      requestHeaders.head.get("x-amz-copy-source-range").map(_.value()) must beSome("bytes=0-10485759")
      requests(1).uri.toString mustEqual "https://s3.eu-west-1.amazonaws.com/destbucket/path/to/dest?partNumber=2&uploadId=some-upload-id"
      requests(1).method mustEqual HttpMethods.PUT
      requestHeaders(1).get("x-amz-copy-source").map(_.value()) must beSome("some-bucket%2Fpath%2Fto%2Fsome%2Fcontent")
      requestHeaders(1).get("x-amz-copy-source-range").map(_.value()) must beSome("bytes=10485760-20971519")
      requests(2).uri.toString mustEqual "https://s3.eu-west-1.amazonaws.com/destbucket/path/to/dest?partNumber=3&uploadId=some-upload-id"
      requests(2).method mustEqual HttpMethods.PUT
      requestHeaders(2).get("x-amz-copy-source").map(_.value()) must beSome("some-bucket%2Fpath%2Fto%2Fsome%2Fcontent")
      requestHeaders(2).get("x-amz-copy-source-range").map(_.value()) must beSome("bytes=20971520-23068672")
    }

    "fail if any of the part uploads fails" in new AkkaTestkitSpecs2Support {
      val requests = scala.collection.mutable.ListBuffer[HttpRequest]()

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        val partNumber = req.uri.query().get("partNumber").getOrElse("XXX")

        val responseContent = s"""<?xml version="1.0" encoding="UTF-8"?>
                                 |<CopyPartResult>
                                 |   <ETag>etag-$partNumber</ETag>
                                 |   <LastModified>another-timestamp</LastModified>
                                 |</CopyPartResult>""".stripMargin
        requests.append(req)
        if(ctr==1) {
          Success(HttpResponse(StatusCodes.InternalServerError))
        } else {
          Success(HttpResponse(StatusCodes.OK, entity = HttpEntity(responseContent)))
        }
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[UploadPart](validator)
      val fakeMeta = HeadInfo("some-bucket","path/to/some/content",None, "last-mod-time",123456L, Some("etag"), "binary/octet-stream", None, None)
      val partsList = Seq(
        UploadPart("destbucket","path/to/dest", 0L, 10*1024*1024L-1, 1),
        UploadPart("destbucket","path/to/dest", 10*1024*1024L, 20*1024*1024L-1, 2),
        UploadPart("destbucket","path/to/dest", 20*1024*1024L, 22*1024*1024L, 3),
      )
      val result = Try {
        Await.result(
          toTest.sendPartCopies(Regions.EU_WEST_1,None,"some-upload-id", partsList, fakeMeta),
          10.seconds
        )
      }

      result must beAFailedTry
      requests.length mustEqual 3
    }

    "fail if akka streams fails" in new AkkaTestkitSpecs2Support {
      val requests = scala.collection.mutable.ListBuffer[HttpRequest]()

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        val partNumber = req.uri.query().get("partNumber").getOrElse("XXX")

        val responseContent = s"""<?xml version="1.0" encoding="UTF-8"?>
                                 |<CopyPartResult>
                                 |   <ETag>etag-$partNumber</ETag>
                                 |   <LastModified>another-timestamp</LastModified>
                                 |</CopyPartResult>""".stripMargin
        requests.append(req)
        if(ctr==1) {
          Failure(new RuntimeException("something bad happened"))
        } else {
          Success(HttpResponse(StatusCodes.OK, entity = HttpEntity(responseContent)))
        }
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[UploadPart](validator)
      val fakeMeta = HeadInfo("some-bucket","path/to/some/content",None, "last-mod-time",123456L, Some("etag"), "binary/octet-stream", None, None)
      val partsList = Seq(
        UploadPart("destbucket","path/to/dest", 0L, 10*1024*1024L-1, 1),
        UploadPart("destbucket","path/to/dest", 10*1024*1024L, 20*1024*1024L-1, 2),
        UploadPart("destbucket","path/to/dest", 20*1024*1024L, 22*1024*1024L, 3),
      )
      val result = Try {
        Await.result(
          toTest.sendPartCopies(Regions.EU_WEST_1,None,"some-upload-id", partsList, fakeMeta),
          10.seconds
        )
      }

      result must beAFailedTry
      requests.length mustEqual 3
    }

    "fail if there is an uncaught exception" in new AkkaTestkitSpecs2Support {
      val requests = scala.collection.mutable.ListBuffer[HttpRequest]()

      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        val partNumber = req.uri.query().get("partNumber").getOrElse("XXX")

        val responseContent = s"""<?xml version="1.0" encoding="UTF-8"?>
                                 |<CopyPartResult>
                                 |   <ETag>etag-$partNumber</ETag>
                                 |   <LastModified>another-timestamp</LastModified>
                                 |</CopyPartResult>""".stripMargin
        requests.append(req)
        if(ctr==1) {
          throw new RuntimeException("this should not happen!!")
        } else {
          Success(HttpResponse(StatusCodes.OK, entity = HttpEntity(responseContent)))
        }
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[UploadPart](validator)
      val fakeMeta = HeadInfo("some-bucket","path/to/some/content",None, "last-mod-time",123456L, Some("etag"), "binary/octet-stream", None, None)
      val partsList = Seq(
        UploadPart("destbucket","path/to/dest", 0L, 10*1024*1024L-1, 1),
        UploadPart("destbucket","path/to/dest", 10*1024*1024L, 20*1024*1024L-1, 2),
        UploadPart("destbucket","path/to/dest", 20*1024*1024L, 22*1024*1024L, 3),
      )
      val result = Try {
        Await.result(
          toTest.sendPartCopies(Regions.EU_WEST_1,None,"some-upload-id", partsList, fakeMeta),
          10.seconds
        )
      }

      result must beAFailedTry
      requests.length mustEqual 2 //exception means that the last one was not carried out
    }
  }

  "ImprovedLargeFileCopier.completeMultipartUpload" should {
    "send the complete upload request with the correct payload" in new AkkaTestkitSpecs2Support {
      var lastRequest:HttpRequest = _
      def validator(ctr:Int, req:HttpRequest, x:Any) = {
        lastRequest = req
        val responseContent = """<?xml version="1.0" encoding="UTF-8"?>
                              |<CompleteMultipartUploadResult>
                              |   <Location>s3://somebucket/path/to/file</Location>
                              |   <Bucket>somebucket</Bucket>
                              |   <Key>path/to/file</Key>
                              |   <ETag>some-etag-here</ETag>
                              |</CompleteMultipartUploadResult>""".stripMargin

        Success(HttpResponse(StatusCodes.OK, entity=HttpEntity(responseContent)))
      }

      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = FakeHostConnectionPool[Any](validator)
      val partsList = Seq(
        UploadedPart(1, "some-id", "etag-1"),
        UploadedPart(2, "some-id", "etag-2"),
        UploadedPart(3, "some-id", "etag-3"),
        UploadedPart(4, "some-id", "etag-4"),
        UploadedPart(5, "some-id", "etag-5"),
      )
      val result = Await.result(
        toTest.completeMultipartUpload(Regions.EU_WEST_1,None, "somebucket", "path/to/file", "some-id", partsList),
        10.seconds
      )

      result mustEqual CompletedUpload("s3://somebucket/path/to/file","somebucket","path/to/file","some-etag-here",None,None,None,None)
      val requestContent = Await.result(
        lastRequest.entity.dataBytes
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .map(_.utf8String)
          .map(scala.xml.XML.loadString),
        2.seconds
      )

      lastRequest.uri.toString() mustEqual "https://s3.eu-west-1.amazonaws.com/somebucket/path/to/file?uploadId=some-id"
      lastRequest.method mustEqual HttpMethods.POST

      (requestContent \\ "Part").length mustEqual partsList.length
      ((requestContent \\ "Part").head \\ "PartNumber").text mustEqual "1"
      ((requestContent \\ "Part").head \\ "ETag").text mustEqual "etag-1"
      ((requestContent \\ "Part")(1) \\ "PartNumber").text mustEqual "2"
      ((requestContent \\ "Part")(1) \\ "ETag").text mustEqual "etag-2"
      ((requestContent \\ "Part")(2) \\ "PartNumber").text mustEqual "3"
      ((requestContent \\ "Part")(2) \\ "ETag").text mustEqual "etag-3"
    }
  }

  "CompletedUpload.fromXML" should {
    "parse the example given in the AWS docs" in {
      val content = """<?xml version="1.0" encoding="UTF-8"?>
                      |<CompleteMultipartUploadResult>
                      |   <Location>location</Location>
                      |   <Bucket>bucket</Bucket>
                      |   <Key>key</Key>
                      |   <ETag>etag</ETag>
                      |   <ChecksumCRC32>crc32</ChecksumCRC32>
                      |   <ChecksumCRC32C>crc32c</ChecksumCRC32C>
                      |   <ChecksumSHA1>sha1</ChecksumSHA1>
                      |   <ChecksumSHA256>sha256</ChecksumSHA256>
                      |</CompleteMultipartUploadResult>""".stripMargin

      val result = ImprovedLargeFileCopier.CompletedUpload.fromXMLString(content)
      result must beASuccessfulTry
      result.get.location mustEqual "location"
      result.get.bucket mustEqual "bucket"
      result.get.key mustEqual "key"
      result.get.eTag mustEqual "etag"
      result.get.crc32 must beSome("crc32")
      result.get.crc32c must beSome("crc32c")
      result.get.sha1 must beSome("sha1")
      result.get.sha256 must beSome("sha256")
    }

    "not fail on missing checksums" in {
      val content = """<?xml version="1.0" encoding="UTF-8"?>
                      |<CompleteMultipartUploadResult>
                      |   <Location>location</Location>
                      |   <Bucket>bucket</Bucket>
                      |   <Key>key</Key>
                      |   <ETag>etag</ETag>
                      |   <ChecksumSHA1>sha1</ChecksumSHA1>
                      |</CompleteMultipartUploadResult>""".stripMargin

      val result = ImprovedLargeFileCopier.CompletedUpload.fromXMLString(content)
      result must beASuccessfulTry
      result.get.location mustEqual "location"
      result.get.bucket mustEqual "bucket"
      result.get.key mustEqual "key"
      result.get.eTag mustEqual "etag"
      result.get.crc32 must beNone
      result.get.crc32c must beNone
      result.get.sha1 must beSome("sha1")
      result.get.sha256 must beNone
    }
  }

  "ImprovedLargeFileCopier.partsFromEtag" should {
    "return the part count from an etag" in {
      ImprovedLargeFileCopier.partsFromEtag("fc6e35aabd934c06676927da84b6a4d5-7") must beSome(7)
      ImprovedLargeFileCopier.partsFromEtag("\"fc6e35aabd934c06676927da84b6a4d5-7\"") must beSome(7)

    }
  }
}
