package controllers

import java.time.ZonedDateTime

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.amazonaws.services.s3.AmazonS3
import com.gu.scanamo.error.DynamoReadError
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common._
import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import io.circe.generic.auto._
import io.circe.syntax._
import models._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import helpers.InjectableRefresher
import play.api.libs.ws.WSClient
import requests.JobSearchRequest
import responses.{GenericErrorResponse, ObjectGetResponse, ObjectListResponse}

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import akka.pattern.ask

@Singleton
class JobController @Inject() (override val config:Configuration, override val controllerComponents:ControllerComponents, jobModelDAO: JobModelDAO,
                               esClientManager: ESClientManager, s3ClientManager: S3ClientManager,
                               ddbClientManager:DynamoClientManager,
                               override val refresher:InjectableRefresher,
                               override val wsClient:WSClient,
                               proxyLocationDAO:ProxyLocationDAO)
                              (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with Circe with JobModelEncoder with ZonedDateTimeEncoder with PanDomainAuthActions with QueryRemaps {

  private val logger = Logger(getClass)

  private implicit val mat:Materializer = ActorMaterializer.create(actorSystem)
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")
  private  val tableName:String = config.get[String]("proxies.tableName")

  protected implicit val indexer = new Indexer(indexName)

  def renderListAction(block: ()=>Future[List[Either[DynamoReadError, JobModel]]]) = APIAuthAction.async {
      val resultFuture = block()
      resultFuture.recover({
        case ex:Throwable=>
          logger.error("Could not render list of jobs: ", ex)
          Future(InternalServerError(GenericErrorResponse("error", ex.toString).asJson))
      })

      resultFuture.map(result => {
        val failures = result.collect({ case Left(err) => err })
        if (failures.nonEmpty) {
          logger.error(s"Can't list all jobs: $failures")
          InternalServerError(GenericErrorResponse("error", failures.map(_.toString).mkString(", ")).asJson)
        } else {
          Ok(ObjectListResponse("ok", "job", result.collect({ case Right(job) => job }), result.length).asJson)
        }
      })
  }

  def getAllJobs(limit:Int, scanFrom:Option[String]) = renderListAction(()=>jobModelDAO.allJobs(limit))

  def jobsFor(fileId:String) = renderListAction(()=>jobModelDAO.jobsForSource(fileId))

  def getJob(jobId:String) = APIAuthAction.async { request=>
    jobModelDAO.jobForId(jobId).map({
      case None=>
        NotFound(GenericErrorResponse("not_found", "Job ID is not found").asJson)
      case Some(Left(err))=>
        logger.error(s"Could not look up job info: ${err.toString}")
        InternalServerError(GenericErrorResponse("db_error", s"Could not look up job: ${err.toString}").asJson)
      case Some(Right(jobModel))=>
        Ok(ObjectGetResponse("ok", "job", jobModel).asJson)
    })
  }

  def jobSearch(clientLimit:Int) = Action.async(circe.json(2048)) { request=>
    implicit val daoImplicit = jobModelDAO
    request.body.as[JobSearchRequest] match {
      case Left(err)=>
        Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson))
      case Right(rq)=>
        rq.runSearch(clientLimit).map({
          case Left(errors)=>
            logger.error("Could not perform job search: ")
            errors.foreach(err=>logger.error(err.toString))
            InternalServerError(GenericErrorResponse("db_error", errors.head.toString).asJson)
          case Right(results)=>
            Ok(ObjectListResponse("ok","job",results, results.length).asJson)
        }).recoverWith({
          case err:Throwable=>
            logger.error("Could not scan jobs: ", err)
            Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
        })
    }
  }
  /**
    * receive a JSON report from an outboard process and handle it
    * @param jobId
    * @return
    */
  def updateStatus(jobId:String) = Action.async(circe.json(2048)) { request=>
    jobModelDAO.jobForId(jobId).flatMap({
      case None=>
        Future(NotFound(GenericErrorResponse("not_found",s"no job found for id $jobId").asJson))
      case Some(Left(err))=>
        Future(InternalServerError(GenericErrorResponse("error", err.toString).asJson))
      case Some(Right(jobDesc))=>
        JobReport.getResult(request.body) match {
          case None=>
            Future(BadRequest(GenericErrorResponse("error","Could not decode any job report from input").asJson))
          case Some(result)=>result match {
            case report:JobReportError=>
              val finalReport = report.decodeLog match {
                case Success(decodedReport)=>
                  logger.error(s"Outboard process indicated job failure (successfully decoded): $decodedReport")
                  decodedReport
                case Failure(err)=>
                  logger.warn(s"Could not decode report: ", err)
                  report
              }
              val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now),jobStatus = JobStatus.ST_ERROR, log=Some(finalReport.log))
              jobModelDAO.putJob(updatedJd)
                .map(result=>Ok(GenericErrorResponse("ok","received report").asJson))

            case report:JobReportSuccess=>
              implicit val esClient = esClientManager.getClient()
              implicit val ddbClient = ddbClientManager.getNewAlpakkaDynamoClient(awsProfile)

              logger.info(s"Outboard process indicated job success: $report")

              val proxyUpdateFuture = JobControllerHelper.thumbnailJobOriginalMedia(jobDesc).flatMap({
                case Left(err)=>Future(Left(err))
                case Right(archiveEntry)=>
                  implicit val s3Client = s3ClientManager.getS3Client(awsProfile, archiveEntry.region)
                  JobControllerHelper.updateProxyRef(report, archiveEntry, proxyLocationDAO, config.getOptional[String]("externalData.region").getOrElse("eu-west-1"))
              })

              proxyUpdateFuture.flatMap({
                case Left(err)=>
                  logger.error(s"Could not update proxy: $err")
                  val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now),log=Some(s"Could not update proxy: $err"), jobStatus = JobStatus.ST_ERROR)
                  jobModelDAO.putJob(updatedJd)
                    .map(result=>Ok(GenericErrorResponse("ok",s"received report but could not update proxy: $err").asJson))
                case Right(msg)=>
                  val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now),jobStatus = JobStatus.ST_SUCCESS)
                  jobModelDAO.putJob(updatedJd)
                    .map(result=>Ok(GenericErrorResponse("ok","received report").asJson))
              })
          }
        }
    })
  }

  def refreshTranscodeInfo(jobId:String) = APIAuthAction.async { request=>
    implicit val timeout:akka.util.Timeout = 30 seconds

//    jobModelDAO.jobForId(jobId).flatMap({
//      case None=>
//        Future(NotFound(GenericErrorResponse("not_found","job ID not found").asJson))
//      case Some(Left(err))=>
//        logger.error(s"Could not read from jobs database: ${err.toString}")
//        Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
//      case Some(Right(jobModel))=>
//        val resultFuture = (etsProxyActor ? ETSProxyActor.ManualJobStatusRefresh(jobModel)).mapTo[ETSMsgReply]
//        resultFuture.map({
//          case ETSProxyActor.PreparationFailure(err)=>
//            logger.error("Could not refresh transcode info", err)
//            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
//          case ETSProxyActor.PreparationSuccess(transcodeId, rtnJobId)=>
//            Ok(ObjectGetResponse("ok","job_id",rtnJobId).asJson)
//        })
//    })
    Future(InternalServerError(GenericErrorResponse("not_implemented","Not currently implemented").asJson))
  }
}
