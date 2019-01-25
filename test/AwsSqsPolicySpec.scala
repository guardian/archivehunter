import org.specs2.mutable.Specification
import io.circe.syntax._
import io.circe.generic.auto._
import models.{AwsSqsPolicy, AwsSqsPolicyDecoder}

class AwsSqsPolicySpec extends Specification with AwsSqsPolicyDecoder {
  "AwsSqsPolicy" should {
    "be generatable from a JSON document returned from the api" in {
      val rawJson =
        """
          |{"Version":"2012-10-17","Id":"MyQueuePolicy","Statement":[{"Sid":"Allow-SendMessage-From-SNS-Topic","Effect":"Allow","Principal":"*","Action":"sqs:SendMessage","Resource":"arn:aws:sns:*:855023211239:*"}]}
        """.stripMargin

        val result = io.circe.parser.parse(rawJson).flatMap(_.as[AwsSqsPolicy])
        result must beRight
    }
  }
}
