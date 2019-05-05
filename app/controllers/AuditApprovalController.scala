package controllers

import java.time.ZonedDateTime
import java.util.{NoSuchElementException, UUID}

import akka.actor.ActorRef
import com.sksamuel.elastic4s.RefreshPolicy
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.{AuditEntryRequestBuilder, AuditStatsHelper, InjectableRefresher}
import javax.inject.{Inject, Named}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{ChartDataResponse, GenericErrorResponse, ObjectListResponse}
import io.circe.generic.auto._
import io.circe.syntax._
import models.{ApprovalStatus, AuditBulkDAO, AuditEntry, AuditEntryClass, UserProfileDAO}
import requests.ManualApprovalRequest
import services.AuditApprovalActor
import services.AuditApprovalActor.{AAMMsg, ApprovalGranted}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._


class AuditApprovalController @Inject()  (override val config:Configuration,
                                          override val controllerComponents: ControllerComponents,
                                          esClientMgr:ESClientManager,
                                          override val wsClient:WSClient,
                                          override val refresher:InjectableRefresher,
                                          auditBulkDAO:AuditBulkDAO,
                                          userProfileDAO:UserProfileDAO,
                                          @Named("auditApprovalActor") auditApprovalActor:ActorRef)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe with AuditEntryRequestBuilder with AdminsOnly {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import akka.pattern.ask

  private val esClient = esClientMgr.getClient()
  private val logger=Logger(getClass)
  val indexName = config.get[String]("externalData.auditIndexName")

  implicit val actorTimeout:akka.util.Timeout = 30.seconds

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

  def sizeByUserAndTime(graphType:Option[String], graphSubLevel:Option[String]) = APIAuthAction.async {
    val subLevelRequest = graphSubLevel match {
      case None=>"requestedBy"
      case Some("region")=>"region"
      case Some("collection")=>"forCollection"
      case Some("user")=>"requestedBy"
      case Some(_)=>"requestedBy"
    }
    esClient.execute {
      graphType match {
        case None => AuditStatsHelper.aggregateBySizeAndTimeQuery(indexName, graphSubLevel=subLevelRequest)
        case Some(wantGraphType) => AuditStatsHelper.aggregateBySizeAndTimeQuery(indexName, AuditEntryClass.withName(wantGraphType),graphSubLevel=subLevelRequest)
      }
    }.map({
      case Left(err)=>
        logger.error(s"Could not look up size aggregation data: $err")
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      case Right(result)=>
        //logger.info(s"Got result: ${result.result.aggregationsAsMap}")
        Ok(AuditStatsHelper.sizeTimeAggregateToChartData("Graph by user and time",result.result.aggregationsAsMap("byDate").asInstanceOf[Map[String,Any]]).asJson)
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

  def monthlyOverview = APIAuthAction.async {
    esClient.execute {
      AuditStatsHelper.monthlyTotalsAggregateQuery(indexName)
    }.map({
      case Left(err)=>
        logger.error(s"Could not look up monthly overview: $err")
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      case Right(result)=>
        logger.info(s"Got result: $result")
        Ok(AuditStatsHelper.simplyifyMonthlyTotalsAggregate("Monthly overview", result.result.aggregationsAsMap("byDate").asInstanceOf[Map[String,Any]]).asJson)
    })
  }

  def addDummyData = APIAuthAction.async {
    val testDataSeq = Seq(
      AuditEntry("fileid-1",2048000,"here","test1",ZonedDateTime.parse("2019-01-01T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test1",ZonedDateTime.parse("2019-02-02T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test2",ZonedDateTime.parse("2019-02-04T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test1",ZonedDateTime.parse("2019-03-03T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test3",ZonedDateTime.parse("2019-03-03T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test3",ZonedDateTime.parse("2019-03-05T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test1",ZonedDateTime.parse("2019-03-01T00:00:00Z"),"some-collection",AuditEntryClass.Download,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test1",ZonedDateTime.parse("2019-02-02T00:00:00Z"),"some-collection",AuditEntryClass.Download,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test2",ZonedDateTime.parse("2019-02-04T00:00:00Z"),"some-collection",AuditEntryClass.Download,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test1",ZonedDateTime.parse("2019-01-03T00:00:00Z"),"some-collection",AuditEntryClass.Download,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test3",ZonedDateTime.parse("2019-01-03T00:00:00Z"),"some-collection",AuditEntryClass.Download,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2048000,"here","test3",ZonedDateTime.parse("2019-01-05T00:00:00Z"),"some-collection",AuditEntryClass.Download,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
    )

    val futureList = Future.sequence(testDataSeq.map(entry => esClient.execute {
      index(indexName,"auditentry") doc entry refresh(RefreshPolicy.Immediate)
    }))

    futureList.map(results=>{
      val failures = results.collect({case Left(err)=>err})
      if(failures.nonEmpty){
        InternalServerError(GenericErrorResponse("error",failures.map(_.toString).mkString("; ")).asJson)
      } else {
        Ok(GenericErrorResponse("ok","dummy data added to index").asJson)
      }
    })
  }

  def approvalsByStatus(statusString:String) = APIAuthAction.async {
    Try { ApprovalStatus.withName(statusString) } match {
      case Success(statusValue)=>
        auditBulkDAO.searchForStatus(statusValue).map({
          case Left(err)=>
            logger.error(s"Could not get approvals by status: $err")
            InternalServerError(GenericErrorResponse("error",err.toString).asJson)
          case Right(result)=>
            Ok(ObjectListResponse("ok","approvalbulk",result,result.length).asJson)
        })
      case Failure(err:NoSuchElementException)=>
        Future(BadRequest(GenericErrorResponse("invalid_input", s"$statusString is not a valid status").asJson))
      case Failure(otherError)=>
        logger.error("Unexpected error looking up value from enum: ", otherError)
        Future(InternalServerError(GenericErrorResponse("unexpected_error", otherError.toString).asJson))
    }
  }

  def manualApprove = APIAuthAction.async(circe.json(2048)) { request=>
    adminsOnlyAsync(request){ userProfile=>
      request.body.as[ManualApprovalRequest].fold(
        err=>
          Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
        reqBody=>
          auditBulkDAO.lookupByUuid(reqBody.bulkId).flatMap({
            case Left(err)=>Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
            case Right(None)=>Future(NotFound(GenericErrorResponse("not_found", "No bulk found with that ID").asJson))
            case Right(Some(auditBulk))=>
              //.get is safe here, userProfile only is None when we log in via HMAC and that is disabled implicitly when calling adminsOnlyAsync with 1 arg
              (auditApprovalActor ? AuditApprovalActor.AdminApprovalOverride(auditBulk, userProfile.get, reqBody.approval, reqBody.notes)).mapTo[AAMMsg].map({
                case AuditApprovalActor.ApprovalGranted(replyText)=>Ok(GenericErrorResponse("ok","Approval granted").asJson)
                case AuditApprovalActor.ApprovalRejected(replyText)=>Forbidden(GenericErrorResponse("problem",s"Approval not granted: $replyText").asJson)
                case AuditApprovalActor.ApprovalPending(replyText)=>Forbidden(GenericErrorResponse("problem",s"Approval still pending: $replyText").asJson)
                case other@_=>
                  logger.error(s"Received an unexpected message reply to manualApprove: ${other.toString}")
                  InternalServerError(GenericErrorResponse("error", "received an unexpected message reply to manualApprove").asJson)
              })
          })
      )


    }
  }
}
