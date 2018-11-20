import akka.stream.Attributes
import akka.util.ByteString
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.profile.path.cred.CredentialsDefaultLocationProvider
import com.amazonaws.regions.{Region, Regions}
import helpers.ParanoidS3Source
import org.specs2.mutable._

import scala.collection.immutable.Seq

class ParanoidS3SourceSpec extends Specification {
  sequential

  "ParanoidS3Source.findParams" should {
    "extract strings from a pseudo-xml byte string source" in new AkkaTestkitSpecs2Support {
      val fakeData =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<root>
          |  <firstKey>firstValue</firstKey>
          |  <secondKey>secondValue</secondKey>
          |  <composite>
          |     <another>
          |       <thirdKey>thirdValue</thirdKey>
          |     </another>
          |   </composite>
          |</root>"""".stripMargin
      val fakeCredsProvider = new AWSCredentialsProviderChain(new InstanceProfileCredentialsProvider())

      val test = new ParanoidS3Source("testBucket",Region.getRegion(Regions.EU_WEST_1), fakeCredsProvider)
      val result = test.findParams(Seq("firstKey","thirdKey","missingKey"),ByteString(fakeData))

      println(result)

      result("firstKey") must beSome("firstValue")
      result("thirdKey") must beSome("thirdValue")
      result("missingKey") must beNone
      result.contains("secondKey") mustEqual false
    }
  }
}
