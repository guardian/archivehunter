package controllers

import java.time.ZonedDateTime
import java.util.UUID
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import auth.{BearerTokenAuth, Security}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.gu.scanamo.error.DynamoReadError
import com.theguardian.multimedia.archivehunter.common._

import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import io.circe.generic.auto._
import io.circe.syntax._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import requests.JobSearchRequest
import responses._

import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import org.slf4j.LoggerFactory
import play.api.cache.SyncCacheApi

@Singleton
class JobController @Inject() (override val config:Configuration,
                               override val controllerComponents:ControllerComponents,
                               jobModelDAO: JobModelDAO,
                               esClientManager: ESClientManager,
                               s3ClientManager: S3ClientManager,
                               ddbClientManager:DynamoClientManager,
                               override val bearerTokenAuth: BearerTokenAuth,
                               override val cache:SyncCacheApi,
                               proxyLocationDAO:ProxyLocationDAO,
                               proxyGenerators:ProxyGenerators)
                              (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with Circe with JobModelEncoder with ZonedDateTimeEncoder with Security with QueryRemaps {

  private val logger=LoggerFactory.getLogger(getClass)

  private implicit val mat:Materializer = ActorMaterializer.create(actorSystem)
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")
  private  val tableName:String = config.get[String]("proxies.tableName")

  protected implicit val indexer = new Indexer(indexName)

  def renderListAction(block: ()=>Future[List[Either[DynamoReadError, JobModel]]]) = IsAuthenticatedAsync { uid=> request=>
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

  def getJob(jobId:String) = IsAuthenticatedAsync { uid=> request=>
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

  def jobSearch(clientLimit:Int) = IsAuthenticatedAsync(circe.json(2048)) { uid=> request=>
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

  def rerunProxy(jobId:String) = IsAuthenticatedAsync { uid=> request=>
    Try { UUID.fromString(jobId) } match {
      case Success(jobUuid) =>
        proxyGenerators.rerunProxyJob(jobUuid).map({
          case Right(jobId) =>
            Ok(ObjectCreatedResponse("ok","jobId",jobId).asJson)
          case Left(err) =>
            InternalServerError(GenericErrorResponse("error", err).asJson)
        })
      case Failure(err) =>
        Future(BadRequest(GenericErrorResponse("error", "You did not input a valid UUID").asJson))
    }
  }

  def refreshTranscodeInfo(jobId:String) = IsAuthenticatedAsync { _=> _=>
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

  def reRunJobsForCollection(collectionName:String) = IsAuthenticatedAsync { _=> _=>
    jobModelDAO.jobsForType("ProblemItemRerun").map(resultList=>{
      val failures = resultList.collect({case Left(err)=>err})
      if(failures.nonEmpty){
        InternalServerError(ErrorListResponse("error","Could not look up jobs", failures.map(_.toString)).asJson)
      }
      val results = resultList.collect({case Right(job)=>job}).filter(_.sourceId==collectionName)
      Ok(ObjectListResponse("ok","Job", results, results.length).asJson)
    })
  }
}
