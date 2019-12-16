package controllers

import akka.actor.ActorSystem
import com.theguardian.multimedia.archivehunter.common.ProxyLocationDAO
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.JobModelDAO
import helpers.InjectableRefresher
import javax.inject.Inject
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class FileMoveController @Inject()(override val config:Configuration,
                                   override val controllerComponents:ControllerComponents,
                                   jobModelDAO: JobModelDAO,
                                   esClientManager: ESClientManager,
                                   s3ClientManager: S3ClientManager,
                                   ddbClientManager:DynamoClientManager,
                                   override val refresher:InjectableRefresher,
                                   override val wsClient:WSClient,
                                   proxyLocationDAO:ProxyLocationDAO,
                                   proxyGenerators:ProxyGenerators)
                                  (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe {

  def moveFile(fileId:String, destCollection:String) = APIAuthAction.async {
    //step one: verify file exists

    //step two: verify dest collection exists

    //step three: gather proxies

    //step four: copy file to new location

    //step five: copy proxies to new location

    //step six: if all copies succeed, remove the old ones

    //step seven: remove tombstones

    Future(Ok(""))
  }
}
