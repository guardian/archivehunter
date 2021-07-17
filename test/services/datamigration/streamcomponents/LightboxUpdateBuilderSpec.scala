package services.datamigration.streamcomponents

import com.amazonaws.services.dynamodbv2.model.AttributeValue
import org.specs2.mutable.Specification

class LightboxUpdateBuilderSpec extends Specification {
  "LightboxUpdateBuilder.attribForType" should {
    "return a string value if that is what's present" in {
      val attrib = new AttributeValue().withS("hello world")

      val toTest = new LightboxUpdateBuilder[String, String]("",a=>a)

      toTest.attribForType(classOf[String], attrib) mustEqual "hello world"
    }
  }
}
