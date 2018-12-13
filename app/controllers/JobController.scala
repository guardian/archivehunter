package controllers

import java.time.ZonedDateTime

import akka.actor.ActorSystem
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.amazonaws.services.s3.AmazonS3
import com.gu.scanamo.error.DynamoReadError
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common._
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import io.circe.generic.auto._
import io.circe.syntax._
import models._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import helpers.InjectableRefresher
import play.api.libs.ws.WSClient
import responses.{GenericErrorResponse, ObjectListResponse}

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class JobController @Inject() (override val config:Configuration, override val controllerComponents:ControllerComponents, jobModelDAO: JobModelDAO,
                               esClientManager: ESClientManager, s3ClientManager: S3ClientManager,
                               ddbClientManager:DynamoClientManager,
                               override val refresher:InjectableRefresher,
                               override val wsClient:WSClient)
                              (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with Circe with JobModelEncoder with ZonedDateTimeEncoder with PanDomainAuthActions {

  private val logger = Logger(getClass)

  private implicit val mat:Materializer = ActorMaterializer.create(actorSystem)
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")
  private  val tableName:String = config.get[String]("proxies.tableName")

  protected implicit val indexer = new Indexer(indexName)
  protected val proxyLocationDAO = new ProxyLocationDAO(tableName)

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
              implicit val s3Client = s3ClientManager.getClient(awsProfile)
              implicit val ddbClient = ddbClientManager.getNewAlpakkaDynamoClient(awsProfile)

              logger.info(s"Outboard process indicated job success: $report")

              val proxyUpdateFuture = JobControllerHelper.thumbnailJobOriginalMedia(jobDesc).flatMap({
                case Left(err)=>Future(Left(err))
                case Right(archiveEntry)=>JobControllerHelper.updateProxyRef(report, archiveEntry, proxyLocationDAO)
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

}
