package controllers

import akka.actor.{ActorRef, ActorSystem}
import com.theguardian.multimedia.archivehunter.common.{ProblemItemIndexer, ProxyLocationDAO}
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModelDAO, ScanTargetDAO}
import helpers.InjectableRefresher
import javax.inject.{Inject, Named}
import org.slf4j.MDC
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectGetResponse}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * controller to return proxy health stats
  * @param config
  * @param controllerComponents
  * @param esClientMgr
  * @param wsClient
  * @param refresher
  */
class ProxyHealthController @Inject()(override val config:Configuration,
                                     override val controllerComponents:ControllerComponents,
                                     esClientMgr:ESClientManager,
                                     override val wsClient:WSClient,
                                     override val refresher:InjectableRefresher)
  extends AbstractController(controllerComponents) with Circe with PanDomainAuthActions
{
  private implicit val esCleint = esClientMgr.getClient()
  private val logger = Logger(getClass)
  private val problemItemIndexer = new ProblemItemIndexer(config.get("externalData.problemItemsIndex"))

  def mostRecentStats = AuthAction.async {
    problemItemIndexer.mostRecentStats.map({
      case Left(err)=>
        MDC.put("reason", err.error.reason)
        MDC.put("response_body", err.body.getOrElse("(none)"))
        logger.error(s"Could not retrieve index stats: $err")
        InternalServerError(GenericErrorResponse("db_error", err.toString))
      case Right(Some(info))=>
        Ok(ObjectGetResponse("ok","ProblemItemCount", info))
      case Right(None)=>
        NotFound(GenericErrorResponse("not_found", "No problem item count data found"))
    })
  }
}