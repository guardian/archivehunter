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

  /*
  data set needs to come out looking like this:
  var ctx = document.getElementById('myChart');
var myChart = new Chart(ctx, {
    type: 'bar',
    data: {
        labels: ['Jan 2019', 'Feb 2019', 'Mar 2019', 'Apr 2019', 'May 2019', 'Jun 2019'],
        datasets: [{
            label: 'John Smith',
            data: [12, 19, 3, 5, 2, 3],
            backgroundColor: [
                'rgba(255, 99, 132, 0.2)',
                'rgba(54, 162, 235, 0.2)',
                'rgba(255, 206, 86, 0.2)',
                'rgba(75, 192, 192, 0.2)',
                'rgba(153, 102, 255, 0.2)',
                'rgba(255, 159, 64, 0.2)'
            ],
            borderColor: [
                'rgba(255, 99, 132, 1)',
                'rgba(54, 162, 235, 1)',
                'rgba(255, 206, 86, 1)',
                'rgba(75, 192, 192, 1)',
                'rgba(153, 102, 255, 1)',
                'rgba(255, 159, 64, 1)'
            ],
            borderWidth: 1
        },{
            label: 'Jane Jones',
            data: [12, 19, 3, 5, 2, 3],
            backgroundColor: [
                'rgba(0, 99, 132, 0.2)',
                'rgba(0, 162, 235, 0.2)',
                'rgba(0, 206, 86, 0.2)',
                'rgba(0, 192, 192, 0.2)',
                'rgba(0, 102, 255, 0.2)',
                'rgba(0, 159, 64, 0.2)'
            ],
            borderColor: [
                'rgba(255, 99, 132, 1)',
                'rgba(54, 162, 235, 1)',
                'rgba(255, 206, 86, 1)',
                'rgba(75, 192, 192, 1)',
                'rgba(153, 102, 255, 1)',
                'rgba(255, 159, 64, 1)'
            ],
            borderWidth: 1
        }]
    },
    options: {
        scales: {
        xAxes: [{
        	stacked: true
        }],
            yAxes: [{
            stacked: true,
                ticks: {
                    beginAtZero: true
                }
            }]
        }
    }
});
   */

  def sizeByUserAndTime = APIAuthAction.async {
    esClient.execute {
      AuditStatsHelper.aggregateBySizeAndTimeQuery(indexName)
    }.map({
      case Left(err)=>
        logger.error(s"Could not look up size aggregation data: $err")
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      case Right(result)=>
        logger.info(s"Got result: $result")
        Ok(AuditStatsHelper.sizeTimeAggregateToChartData("Graph by user and time",result.result.aggregationsAsMap).asJson)
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
        Ok(ChartDataResponse.fromAggregatesMap[Double](result.result.aggregationsAsMap, "totalSize").asJson)
    })
  }
}
