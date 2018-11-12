import java.time.{OffsetDateTime, ZoneOffset}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpHeader.ParsingResult.{Error, Ok}
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{ImplicitSender, TestKit}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, BasicSessionCredentials}
import com.amazonaws.regions.{Region, Regions}
import helpers.S3Signer
import org.specs2.mutable._
import play.api.Logger

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/* A tiny class that can be used as a Specs2 ‘context’. */
abstract class AkkaTestkitSpecs2Support extends TestKit(ActorSystem())
  with After
  with ImplicitSender {
  // make sure we shut down the actor system after all tests have run
  def after = system.terminate()
}

class S3LocationSpec extends Specification {
  class TestClass(loggerval:Logger, m:Materializer, ecval:ExecutionContext) extends S3Signer {
    override implicit val mat:Materializer = m
    override protected val logger=loggerval
    override implicit val ec:ExecutionContext = ecval
  }

  "S3Signer" should {
    "sign a sample GET request correctly" in new AkkaTestkitSpecs2Support {
      import scala.collection.immutable.Seq
      val logger=Logger(getClass)
      val mat = ActorMaterializer()
      implicit val ec = system.dispatcher
      val test = new TestClass(logger, mat, ec)

      val credentialsProvider = new AWSStaticCredentialsProvider(new BasicAWSCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
//      val testInput = new HttpRequest(
//        "GET",
//        "https://examplebucket.s3.amazonaws.com/test.txt",
//        Seq(
//          HttpHeader("Range", "bytes=0-9")
//        ),
//        HttpEntity.NoEntity,
//        HttpProtocol.HTTP_1_1
//      )

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
  }
}
