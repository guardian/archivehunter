package controllers

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.Materializer
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.PathCacheIndexer
import helpers.InjectableRefresher
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{CountResponse, GenericErrorResponse}
import io.circe.syntax._
import io.circe.generic.auto._

import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Future

@Singleton
class PathCacheController @Inject()(override val config:Configuration,
                                  override val controllerComponents:ControllerComponents,
                                  override val wsClient:WSClient,
                                  override val refresher:InjectableRefresher,
                                    esClientMgr:ESClientManager)
                                 (implicit actorSystem:ActorSystem, mat:Materializer)
  extends AbstractController(controllerComponents) with Circe with PanDomainAuthActions with AdminsOnly {
  private val logger = LoggerFactory.getLogger(getClass)
  private lazy val esClient = esClientMgr.getClient()

  private lazy val indexer = new PathCacheIndexer(config.getOptional[String]("externalData.pathCacheIndex").getOrElse("pathcache"), esClient)

  def pathCacheSize() = APIAuthAction.async { request=>
    indexer.size()
      .map(indexSize=>Ok(CountResponse("ok","pathCacheIndex", indexSize).asJson))
      .recover({
        case err:Throwable=>
          logger.error(s"could not process pathCacheSize: ${err.getMessage}", err)
          InternalServerError(GenericErrorResponse("error",err.getMessage).asJson)
      })
  }

  def startCacheBuild(blankFirst: Boolean) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {
      val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

        val removalFut = if(blankFirst) { indexer.removeIndex } else { Future( () ) }

        removalFut.map(_=>{
          indexer.buildIndex(indexName)
          Ok(GenericErrorResponse("ok", "index build started").asJson)
        }).recover({
          case err:Throwable=>
          logger.error(s"could not start index cache build: ${err.getMessage}", err)
          InternalServerError(GenericErrorResponse("error", err.getMessage).asJson)
        })
      }
    }
}

