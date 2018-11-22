package clientManagers

import com.amazonaws.services.ecs.{AmazonECS, AmazonECSClientBuilder}
import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class ECSClientManager  @Inject() (config:Configuration) extends ClientManagerBase[AmazonECS]{
  override def getClient(profileName: Option[String]): AmazonECS =
    AmazonECSClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()
}
