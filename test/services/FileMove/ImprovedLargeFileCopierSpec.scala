package services.FileMove

import TestFileMove.AkkaTestkitSpecs2Support
import akka.http.scaladsl.ConnectionContext
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import com.amazonaws.regions.Regions
import org.specs2.mutable.Specification

import javax.net.ssl.{SSLContext, SSLEngine}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class ImprovedLargeFileCopierSpec extends Specification {
  sequential

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
}
