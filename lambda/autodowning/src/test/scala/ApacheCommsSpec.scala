import com.amazonaws.services.lambda.runtime.LambdaLogger
import models.AkkaMember
import org.apache.http.client.{ClientProtocolException, HttpResponseException}
import org.apache.http.{HttpEntity, StatusLine}
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.impl.client.CloseableHttpClient
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import software.amazon.awssdk.utils.StringInputStream

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.util.{Success, Try}

class ApacheCommsSpec extends Specification with Mockito {
  implicit val logToConsole:LambdaLogger = new LambdaLogger {
    override def log(string: String): Unit = println(string)
  }

  "ApacheComms.getNodes" should {
    "parse the list-node output from akka management" in {
      val fakeResponse =
        """ {
          |   "selfNode": "akka.tcp://test@10.10.10.10:1111",
          |   "members": [
          |     {
          |       "node": "akka.tcp://test@10.10.10.10:1111",
          |       "nodeUid": "1116964444",
          |       "status": "Up",
          |       "roles": []
          |     }
          |   ],
          |   "unreachable": [],
          |   "leader": "akka.tcp://test@10.10.10.10:1111",
          |   "oldest": "akka.tcp://test@10.10.10.10:1111"
          | }""".stripMargin

      val mockHttpClient = mock[CloseableHttpClient]
      val mockStatusLine = mock[StatusLine]
      mockStatusLine.getStatusCode returns 200
      mockStatusLine.getReasonPhrase returns "OK"

      val mockEntity = mock[HttpEntity]
      mockEntity.getContent returns new ByteArrayInputStream(fakeResponse.getBytes(StandardCharsets.UTF_8))

      val mockHttpResponse = mock[CloseableHttpResponse]
      mockHttpResponse.getStatusLine returns mockStatusLine
      mockHttpResponse.getEntity returns mockEntity
      mockHttpClient.execute(any) returns mockHttpResponse

      val toTest = new ApacheComms("myhost.com", 8558) {
        override protected val httpClient: Try[CloseableHttpClient] = Success(mockHttpClient)
      }

      val result = toTest.getNodes()
      result must beASuccessfulTry
      result.get.headOption must beSome(AkkaMember(new URI("akka.tcp://test@10.10.10.10:1111"), "1116964444", "Up", Seq()))
      there was one(mockHttpClient).execute(any)
    }

    "gracefully handle exceptions from apache http" in {
      val mockHttpClient = mock[CloseableHttpClient]
      val mockStatusLine = mock[StatusLine]
      mockStatusLine.getStatusCode returns 200
      mockStatusLine.getReasonPhrase returns "OK"

      val mockEntity = mock[HttpEntity]
      mockEntity.getContent throws new RuntimeException("Kersplaaaat!")

      val mockHttpResponse = mock[CloseableHttpResponse]
      mockHttpResponse.getStatusLine returns mockStatusLine
      mockHttpResponse.getEntity returns mockEntity
      mockHttpClient.execute(any) returns mockHttpResponse

      val toTest = new ApacheComms("myhost.com", 8558) {
        override protected val httpClient: Try[CloseableHttpClient] = Success(mockHttpClient)
      }

      val result = toTest.getNodes()
      there was one(mockHttpClient).execute(any)
      result must beAFailedTry
      result.failed.get.getMessage mustEqual "Kersplaaaat!"
    }

    "fail on 500 error" in {
      val fakeResponse =
        """{"error":"something broke badly"}""".stripMargin

      val mockHttpClient = mock[CloseableHttpClient]
      val mockStatusLine = mock[StatusLine]
      mockStatusLine.getStatusCode returns 500
      mockStatusLine.getReasonPhrase returns "OK"

      val mockEntity = mock[HttpEntity]
      mockEntity.getContent returns new ByteArrayInputStream(fakeResponse.getBytes(StandardCharsets.UTF_8))

      val mockHttpResponse = mock[CloseableHttpResponse]
      mockHttpResponse.getStatusLine returns mockStatusLine
      mockHttpResponse.getEntity returns mockEntity
      mockHttpClient.execute(any) throws new HttpResponseException(500, "Internal server error")

      val toTest = new ApacheComms("myhost.com", 8558) {
        override protected val httpClient: Try[CloseableHttpClient] = Success(mockHttpClient)
      }

      val result = toTest.getNodes()
      result must beAFailedTry
      there was one(mockHttpClient).execute(any)
      result.failed.get.getMessage mustEqual "status code: 500, reason phrase: Internal server error"
    }
  }
}
