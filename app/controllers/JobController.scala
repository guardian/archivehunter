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
import responses.{GenericErrorResponse, ObjectListResponse}

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class JobController @Inject() (config:Configuration, cc:ControllerComponents, jobModelDAO: JobModelDAO,
                               esClientManager: ESClientManager, s3ClientManager: S3ClientManager,ddbClientManager:DynamoClientManager)
                              (implicit actorSystem:ActorSystem) extends AbstractController(cc) with Circe with JobModelEncoder with ZonedDateTimeEncoder {
  private val logger = Logger(getClass)

  private implicit val mat:Materializer = ActorMaterializer.create(actorSystem)
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")
  private  val tableName:String = config.get[String]("proxies.tableName")

  protected val indexer = new Indexer(indexName)
  protected val proxyLocationDAO = new ProxyLocationDAO(tableName)

  def renderListAction(block: ()=>Future[List[Either[DynamoReadError, JobModel]]]) = Action.async {
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

  def thumbnailJobOriginalMedia(jobDesc:JobModel)(implicit esClient:HttpClient) = jobDesc.sourceType match {
    case SourceType.SRC_MEDIA=>
      indexer.getById(jobDesc.sourceId).map(result=>Right(result))
    case SourceType.SRC_PROXY=>
      Future(Left("need original media!"))
    case SourceType.SRC_THUMBNAIL=>
      Future(Left("need original media!"))
  }

  def updateProxyRef(report:JobReportSuccess, archiveEntry:ArchiveEntry)(implicit s3Client:AmazonS3, dynamoClient:DynamoClient) = ProxyLocation
    .fromS3(proxyUri=report.output,mainMediaUri=s"s3://${archiveEntry.bucket}/${archiveEntry.path}", Some(ProxyType.THUMBNAIL))
    .flatMap({
      case Left(err)=>
        logger.error(s"Could not get proxy location: $err")
        Future(Left(err))
      case Right(proxyLocation)=>
        logger.info("Saving proxy location...")
        proxyLocationDAO.saveProxy(proxyLocation).map({
          case None=>
            Right("Updated with no data back")
          case Some(Left(err))=>
            Left(err.toString)
          case Some(Right(updatedLocation))=>
            logger.info(s"Updated location: $updatedLocation")
            Right(s"Updated $updatedLocation")
        })
    })

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

              val proxyUpdateFuture = thumbnailJobOriginalMedia(jobDesc).flatMap({
                case Left(err)=>Future(Left(err))
                case Right(archiveEntry)=>updateProxyRef(report, archiveEntry)
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
