package services.datamigration.streamcomponents

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration

class LightboxUpdateSinkSpec extends Specification with Mockito {
  implicit val system = ActorSystem("LightboxUpdateSink",Configuration.empty)
  implicit val mat = Materializer.matFromSystem

  def stringValue(s:String) = new AttributeValue().withS(s)

  "LightboxUpdateSink" should {
    "work" in {
      val testUpdate = UpdateRequest(
        Map("pk"->stringValue("testitem"),"field"->stringValue("value")),
        Map("pk"->stringValue("deleteitem"), "field"->stringValue("othervalue"))
      )

      val mockClient = mock[AmazonDynamoDBClient]
      mockClient.batchWriteItem()
      val config = Configuration.empty
      val mockClientMgr = mock[DynamoClientManager]

      Source.single(testUpdate).toMat(LightboxUpdateSink("sometable", config,))
    }
  }
}
