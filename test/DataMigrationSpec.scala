import akka.actor.ActorSystem
import akka.stream.Materializer
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntryDAO
import helpers.UserAvatarHelper
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration

class DataMigrationSpec extends Specification with Mockito {
  "emailUpdater" should {
    "replace the email domain if required and return Some data" in {
      val toTest = new DataMigration(Configuration.empty, mock[DynamoClientManager], mock[ESClientManager], mock[UserAvatarHelper])(mock[ActorSystem], mock[Materializer])

      toTest.emailUpdater(new AttributeValue().withS("fred@guardian.co.uk")) must beSome(new AttributeValue().withS("fred@theguardian.com"))
    }

    "return None if no replacement is required" in {
      val toTest = new DataMigration(Configuration.empty, mock[DynamoClientManager], mock[ESClientManager], mock[UserAvatarHelper])(mock[ActorSystem], mock[Materializer])

      toTest.emailUpdater(new AttributeValue().withS("fred@theguardian.com")) must beNone
    }
  }
}
