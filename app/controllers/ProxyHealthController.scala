package controllers

import com.theguardian.multimedia.archivehunter.common.{ProblemItemHitReader, ProblemItemIndexer, ProxyTypeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProblemItem
import helpers.InjectableRefresher
import javax.inject.Inject
import org.slf4j.MDC
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectGetResponse, ObjectListResponse, TermsBucketResponse}
import io.circe.syntax._
import io.circe.generic.auto._

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
  extends AbstractController(controllerComponents) with Circe with PanDomainAuthActions with ProblemItemHitReader with ProxyTypeEncoder
{
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._

  private implicit val esCleint = esClientMgr.getClient()
  private val logger = Logger(getClass)

  private val problemItemIndexName = config.get[String]("externalData.problemItemsIndex")
  private val problemItemIndexer = new ProblemItemIndexer(problemItemIndexName)

  private val problemSummaryIndexer = new ProblemItemIndexer(config.get[String]("externalData.problemSummaryIndex"))

  def mostRecentStats = APIAuthAction.async {
    problemSummaryIndexer.mostRecentStats.map({
      case Left(err)=>
        MDC.put("reason", err.error.reason)
        MDC.put("response_body", err.body.getOrElse("(none)"))
        logger.error(s"Could not retrieve index stats: $err")
        InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
      case Right(Some(info))=>
        Ok(ObjectGetResponse("ok","ProblemItemCount", info).asJson)
      case Right(None)=>
        NotFound(GenericErrorResponse("not_found", "No problem item count data found").asJson)
    })
  }

  def itemsList(collection:Option[String], pathRegex:Option[String], start:Int, size:Int) = APIAuthAction.async {
    val queries = Seq(
      collection.map(collectionFilter=>termsQuery("collection.keyword", collectionFilter))

    ).collect({case Some(q)=>q})

    esCleint.execute {
      search(problemItemIndexName) query boolQuery().withMust(queries) start start limit size
    }.map({
      case Left(err)=>
        MDC.put("reason", err.error.reason)
        MDC.put("response_body", err.body.getOrElse("(none)"))
        logger.error(s"Could not list problem items: $err")
        InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
      case Right(result)=>
        Ok(ObjectListResponse("ok","ProblemItem", result.result.to[ProblemItem], result.result.totalHits.toInt).asJson)
    })
  }

  def collectionsWithProblems = APIAuthAction.async {
    esCleint.execute {
      search(problemItemIndexName) aggs termsAgg("collections","collection.keyword")
    }.map({
      case Left(err)=>
        MDC.put("reason", err.error.reason)
        MDC.put("response_body", err.body.getOrElse("(none)"))
        logger.error(s"Could not list problem collections: $err")
        InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
      case Right(result)=>
        val collectionsData = result.result.aggregationsAsMap("collections")
        logger.debug(collectionsData.toString)

        Ok(TermsBucketResponse.fromRawData("ok", collectionsData).asJson)
    })
  }
}