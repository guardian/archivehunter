package controllers

import java.time.ZonedDateTime

import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.{AuditEntryRequestBuilder, AuditStatsHelper, InjectableRefresher}
import javax.inject.Inject
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{ChartDataResponse, GenericErrorResponse}
import io.circe.generic.auto._
import io.circe.syntax._

import scala.concurrent.ExecutionContext.Implicits.global

class AuditApprovalController @Inject()  (override val config:Configuration,
          override val controllerComponents: ControllerComponents,
          esClientMgr:ESClientManager,
          override val wsClient:WSClient,
          override val refresher:InjectableRefresher)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe with AuditEntryRequestBuilder{
  import com.sksamuel.elastic4s.http.ElasticDsl._

  private val esClient = esClientMgr.getClient()
  private val logger=Logger(getClass)
  val indexName = config.get[String]("externalData.auditIndexName")

  def sizeByUserAndTime = APIAuthAction.async {
    esClient.execute {
      AuditStatsHelper.aggregateBySizeAndTimeQuery(indexName)
    }.map({
      case Left(err)=>
        logger.error(s"Could not look up size aggregation data: $err")
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      case Right(result)=>
        logger.info(s"Got result: $result")
        Ok(ChartDataResponse.fromAggregatesMap(result.result.aggregationsAsMap,"byDate").asJson)
    })
  }

  def totalForCurrentMonth = APIAuthAction.async {
    val nowTime = ZonedDateTime.now()
    esClient.execute {
      AuditStatsHelper.totalDataForMonth(indexName, nowTime.getMonth.getValue, nowTime.getYear)
    }.map({
      case Left(err)=>
        logger.error(s"Could not look up current month total: $err")
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      case Right(result)=>
        logger.info(s"Got result: $result")
        Ok(result.result.aggregationsAsMap("totalSize").asJson)
    })
  }
}
