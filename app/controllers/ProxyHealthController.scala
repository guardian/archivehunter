package controllers

import akka.actor.ActorRef
import auth.{BearerTokenAuth, Security}
import com.theguardian.multimedia.archivehunter.common.{ProblemItemHitReader, ProblemItemIndexer, ProxyTypeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobModelDAO, ProblemItem, ProxyHealthEncoder}

import javax.inject.{Inject, Named, Singleton}
import org.slf4j.{LoggerFactory, MDC}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses._
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.cache.SyncCacheApi
import services.ProblemItemRetry

import scala.annotation.switch
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * controller to return proxy health stats
  * @param config
  * @param controllerComponents
  * @param esClientMgr
  * @param wsClient
  * @param refresher
  */
@Singleton
class ProxyHealthController @Inject()(override val config:Configuration,
                                     override val controllerComponents:ControllerComponents,
                                     esClientMgr:ESClientManager,
                                      override val bearerTokenAuth:BearerTokenAuth,
                                      override val cache:SyncCacheApi,
                                      @Named("problemItemRetry") problemItemRetry:ActorRef)
  extends AbstractController(controllerComponents) with Circe with Security with ProblemItemHitReader with ProxyTypeEncoder with ProxyHealthEncoder
{
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import akka.pattern.ask

  private implicit val esCleint = esClientMgr.getClient()
  private val logger=LoggerFactory.getLogger(getClass)

  private val problemItemIndexName = config.get[String]("externalData.problemItemsIndex")
  private val problemItemIndexer = new ProblemItemIndexer(problemItemIndexName)

  private val problemSummaryIndexer = new ProblemItemIndexer(config.get[String]("externalData.problemSummaryIndex"))

  def mostRecentStats = IsAuthenticatedAsync { _=> _=>
    problemSummaryIndexer.mostRecentStats.map({
      case Left(err)=>
        MDC.put("reason", err.reason)
        logger.error(s"Could not retrieve index stats: $err")
        InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
      case Right(Some(info))=>
        Ok(ObjectGetResponse("ok","ProblemItemCount", info).asJson)
      case Right(None)=>
        NotFound(GenericErrorResponse("not_found", "No problem item count data found").asJson)
    })
  }

  def itemsList(collection:Option[String], pathRegex:Option[String], start:Int, size:Int) = IsAuthenticatedAsync { _=> _=>
    val queries = Seq(
      collection.map(collectionFilter=>termsQuery("collection.keyword", collectionFilter))

    ).collect({case Some(q)=>q})

    esCleint.execute {
      search(problemItemIndexName) query boolQuery().withMust(queries) start start limit size
    }.map(response=>{
      (response.status: @switch) match {
        case 200=>
          Ok(ObjectListResponse("ok","ProblemItem", response.result.to[ProblemItem], response.result.totalHits.toInt).asJson)
        case _=>
          MDC.put("reason", response.error.reason)
          MDC.put("response_body", response.body.getOrElse("(none)"))
          logger.error(s"Could not list problem items: ${response.error.reason}")
          InternalServerError(GenericErrorResponse("db_error", response.error.reason).asJson)
      }
    })
  }

  def collectionsWithProblems = IsAuthenticatedAsync { _=> _=>
    esCleint.execute {
      search(problemItemIndexName) aggs termsAgg("collections","collection.keyword")
    }.map(response=>{
      (response.status: @switch) match {
        case 200=>
          val collectionsData = response.result.aggregationsAsMap("collections")
          logger.debug(collectionsData.toString)

          Ok(TermsBucketResponse.fromRawData("ok", collectionsData).asJson)
        case _=>
          MDC.put("reason", response.error.reason)
          MDC.put("response_body", response.body.getOrElse("(none)"))
          logger.error(s"Could not list problem collections: ${response.error.reason}")
          InternalServerError(GenericErrorResponse("db_error", response.error.reason).asJson)
      }
    })
  }

  def triggerProblemItemsFor(collectionName:String) = IsAuthenticatedAsync { _=> _=>
    implicit val timeout:akka.util.Timeout = 10.seconds

    try {
      (problemItemRetry ? ProblemItemRetry.RetryForCollection(collectionName)).map({
        case akka.actor.Status.Success=>
          Ok(GenericErrorResponse("ok", "Scan started").asJson)
        case akka.actor.Status.Failure(err)=>
          InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      })

    } catch {
      case err:Throwable=>
        Future(InternalServerError(GenericErrorResponse("error",err.toString).asJson))
    }
  }

}