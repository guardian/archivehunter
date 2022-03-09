import java.time.{OffsetDateTime, ZoneOffset}
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpHeader.ParsingResult.{Error, Ok}
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{ImplicitSender, TestKit}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, BasicSessionCredentials}
import com.amazonaws.regions.{Region, Regions}
import helpers.S3Signer
import org.slf4j.LoggerFactory
import org.specs2.mutable._
import play.api.Logger
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.collection.immutable.Seq

class S3LocationSpec extends Specification {
  sequential

  val logger = LoggerFactory.getLogger(getClass)
  class TestClass(loggerval:org.slf4j.Logger, m:Materializer, ecval:ExecutionContext) extends S3Signer {
    override implicit val mat:Materializer = m
    override protected val logger=loggerval
    override implicit val ec:ExecutionContext = ecval
  }

  "S3Signer" should {
    "sign a sample GET request correctly" in new AkkaTestkitSpecs2Support {
      /* example taken from https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html */
      implicit val mat = Materializer.matFromSystem
      implicit val ec = system.dispatcher
      val test = new TestClass(logger, mat, ec)

      val credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))

      val headers = Seq(HttpHeader.parse("Range", "bytes=0-9"),HttpHeader.parse("Host", "examplebucket.s3.amazonaws.com")).map({
        case Ok(result, errors)=>result
        case Error(errs)=>throw new RuntimeException(errs.toString)
      })

      val testInput = HttpRequest(HttpMethod.custom("GET"),
        Uri("https://examplebucket.s3.amazonaws.com/test.txt"),
        headers
      )

      val fakeTime = OffsetDateTime.of(2013, 5, 24, 0,0,0,0,ZoneOffset.UTC)
      val result = Await.result(test.signHttpRequest(testInput,Region.getRegion(Regions.US_EAST_1),"s3", credentialsProvider, Some(fakeTime)), 10 seconds)

      val headerMap = result.headers.map(hdr=>Tuple2(hdr.name(), hdr.value())).toMap
      headerMap("Authorization") mustEqual "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
    }

    "sign a GET request with parameters correctly" in new AkkaTestkitSpecs2Support {
      val mat = Materializer.matFromSystem
      implicit val ec = system.dispatcher
      val test = new TestClass(logger, mat, ec)

      val credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))

      val headers = Seq(HttpHeader.parse("Host", "examplebucket.s3.amazonaws.com")).map({
        case Ok(result, errors)=>result
        case Error(errs)=>throw new RuntimeException(errs.toString)
      })

      val testInput = HttpRequest(HttpMethod.custom("GET"),
        Uri("https://examplebucket.s3.amazonaws.com/?lifecycle"),
        headers
      )

      val fakeTime = OffsetDateTime.of(2013, 5, 24, 0,0,0,0,ZoneOffset.UTC)
      val result = Await.result(test.signHttpRequest(testInput,Region.getRegion(Regions.US_EAST_1),"s3", credentialsProvider, Some(fakeTime)), 10 seconds)

      val headerMap = result.headers.map(hdr=>Tuple2(hdr.name(), hdr.value())).toMap
      headerMap("Authorization") mustEqual "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=host;x-amz-content-sha256;x-amz-date,Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"
    }

    "sign a GET request with value parameters correctly" in new AkkaTestkitSpecs2Support {
      val mat = Materializer.matFromSystem
      implicit val ec = system.dispatcher
      val test = new TestClass(logger, mat, ec)

      val credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))

      val headers = Seq(HttpHeader.parse("Host", "examplebucket.s3.amazonaws.com")).map({
        case Ok(result, errors)=>result
        case Error(errs)=>throw new RuntimeException(errs.toString)
      })

      val testInput = HttpRequest(HttpMethod.custom("GET"),
        Uri("https://examplebucket.s3.amazonaws.com/?max-keys=2&prefix=J"),
        headers
      )

      val fakeTime = OffsetDateTime.of(2013, 5, 24, 0,0,0,0,ZoneOffset.UTC)
      val result = Await.result(test.signHttpRequest(testInput,Region.getRegion(Regions.US_EAST_1),"s3", credentialsProvider, Some(fakeTime)), 10 seconds)

      val headerMap = result.headers.map(hdr=>Tuple2(hdr.name(), hdr.value())).toMap
      headerMap("Authorization") mustEqual "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=host;x-amz-content-sha256;x-amz-date,Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"
    }

    "sign a PUT request with data correctly" in new AkkaTestkitSpecs2Support {
      val mat = Materializer.matFromSystem
      implicit val ec = system.dispatcher
      val test = new TestClass(logger, mat, ec)

      val credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))

      val headers = Seq(
        HttpHeader.parse("Host", "examplebucket.s3.amazonaws.com"),
        HttpHeader.parse("Date", "Fri, 24 May 2013 00:00:00 GMT"),
        HttpHeader.parse("x-amz-storage-class", "REDUCED_REDUNDANCY")
      ).map({
        case Ok(result, errors)=>result
        case Error(errs)=>throw new RuntimeException(errs.toString)
      })

      val testData = HttpEntity("Welcome to Amazon S3.")

      val testInput = HttpRequest(HttpMethods.PUT,
        Uri("https://examplebucket.s3.amazonaws.com/test$file.text"),
        headers,
        entity = testData
      )

      val fakeTime = OffsetDateTime.of(2013, 5, 24, 0,0,0,0,ZoneOffset.UTC)
      val result = Await.result(test.signHttpRequest(testInput,Region.getRegion(Regions.US_EAST_1),"s3", credentialsProvider, Some(fakeTime)), 10 seconds)

      val headerMap = result.headers.map(hdr=>Tuple2(hdr.name(), hdr.value())).toMap
      headerMap("Authorization") mustEqual "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"
    }
  }
}
