package controllers

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo._
import com.gu.scanamo.syntax._
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.mvc._
import io.circe.generic.auto._
import io.circe.syntax._
import play.api.libs.circe.Circe
import responses.{GenericErrorResponse, ObjectCreatedResponse, ObjectGetResponse, ObjectListResponse}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import helpers.InjectableRefresher
import play.api.libs.ws.WSClient
import services.{BucketScanner, BulkThumbnailer, LegacyProxiesScanner}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

@Singleton
class ScanTargetController @Inject() (@Named("bucketScannerActor") bucketScanner:ActorRef,
                                      @Named("legacyProxiesScannerActor") proxyScanner:ActorRef,
                                      @Named("bulkThumbnailerActor") bulkThumbnailer: ActorRef,
                                      override val config:Configuration,
                                      override val controllerComponents:ControllerComponents,
                                      override val wsClient:WSClient,
                                      override val refresher:InjectableRefresher,
                                      ddbClientMgr:DynamoClientManager,
                                      proxyGenerators:ProxyGenerators,
                                      scanTargetDAO:ScanTargetDAO,
                                      jobModelDAO:JobModelDAO)
                                     (implicit system:ActorSystem)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe with ZonedDateTimeEncoder with ZonedTimeFormat with JobModelEncoder with AdminsOnly {
  private val logger=Logger(getClass)
  implicit val mat:Materializer = ActorMaterializer.create(system)

  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))

  private val profileName = config.getOptional[String]("externalData.awsProfile")

  private val conventionalClient = ddbClientMgr.getNewDynamoClient(profileName)
  private val alpakkaClient = ddbClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  def newTarget = APIAuthAction(circe.json[ScanTarget]) { scanTarget=>
    logger.debug(scanTarget.body.toString)
    Scanamo.exec(conventionalClient)(table.put(scanTarget.body)).map({
      case Left(writeError)=>
        InternalServerError(GenericErrorResponse("error",writeError.toString).asJson)
      case Right(createdScanTarget)=>
        Ok(ObjectCreatedResponse[String]("created","scan_target",createdScanTarget.bucketName).asJson)
    }).getOrElse(Ok(ObjectCreatedResponse[Option[String]]("created","scan_target",None).asJson))
  }

  def removeTarget(targetName:String) = APIAuthAction { request=>
    adminsOnlySync(request) {
      val r = Scanamo.exec(ddbClientMgr.getNewDynamoClient(profileName))(table.delete('bucketName -> targetName))
      Ok(ObjectCreatedResponse[String]("deleted", "scan_target", targetName).asJson)
    }
  }

  def get(targetName:String) = APIAuthAction { request=>
    adminsOnlySync(request) {
      Scanamo.exec(ddbClientMgr.getNewDynamoClient(profileName))(table.get('bucketName -> targetName)).map({
        case Left(err) =>
          InternalServerError(GenericErrorResponse("database_error", err.toString).asJson)
        case Right(result) =>
          Ok(ObjectGetResponse[ScanTarget]("ok", "scan_target", result).asJson)
      }).getOrElse(NotFound(ObjectCreatedResponse[String]("not_found", "scan_target", targetName).asJson))
    }
  }

  def listScanTargets = APIAuthAction.async { request=>
    adminsOnlyAsync(request) { maybeUserProfile=>
      ScanamoAlpakka.exec(alpakkaClient)(table.scan()).map({ result =>
        val errors = result.collect({
          case Left(readError) => readError
        })

        if (errors.isEmpty) {
          val success = result.collect({
            case Right(scanTarget) => scanTarget
          })
          Ok(ObjectListResponse[List[ScanTarget]]("ok", "scan_target", success, success.length).asJson)
        } else {
          errors.foreach(err => logger.error(err.toString))
          InternalServerError(GenericErrorResponse("error", errors.map(_.toString).mkString(",")).asJson)
        }
      })
    }
  }

  private def withLookup(targetName:String)(block: ScanTarget=>Result) = Scanamo.exec(conventionalClient)(table.get('bucketName -> targetName )).map({
    case Left(error)=>
      InternalServerError(GenericErrorResponse("error", error.toString).asJson)
    case Right(tgt)=>
      block(tgt)
  }).getOrElse(NotFound(ObjectCreatedResponse[String]("not_found","scan_target",targetName).asJson))

  private def withLookupAsync(targetName:String)(block: ScanTarget=>Future[Result]) = Scanamo.exec(conventionalClient)(table.get('bucketName -> targetName )).map({
    case Left(error)=>
      Future(InternalServerError(GenericErrorResponse("error", error.toString).asJson))
    case Right(tgt)=>
      block(tgt)
  }).getOrElse(Future(NotFound(ObjectCreatedResponse[String]("not_found","scan_target",targetName).asJson)))

  /**
    * tries to create and save a scan job and then calls the block with the saved result.
    * if the db operation fails then an InternalServerError is returned
    * @param targetName target name being scanned
    * @param jobType job type name to use
    * @param block a block receiving the JobModel and returning a Future of Result
    * @return either the Result of the block as a future or an InternalServerError describing the database failure
    */
  private def withNewScanJob(tgt:ScanTarget, jobType:String)(block: JobModel=>Result) = {
    val jobUuid = UUID.randomUUID()
    val job = JobModel(jobUuid.toString,jobType,Some(ZonedDateTime.now()),None,JobStatus.ST_RUNNING,None,tgt.bucketName,None,SourceType.SRC_SCANTARGET,lastUpdatedTS=None)

    jobModelDAO.putJob(job).map({
      case None=>
        val updatedTarget = tgt.withAnotherPendingJob(job.jobId)
        scanTargetDAO.put(updatedTarget)
        block(job)
      case Some(Right(rec))=>
        val updatedTarget = tgt.withAnotherPendingJob(job.jobId)
        scanTargetDAO.put(updatedTarget)
        block(job)
      case Some(Left(err))=>
        InternalServerError(GenericErrorResponse("db_error",err.toString).asJson)
    })
  }

  def manualTrigger(targetName:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) { maybeUserProfile=>
      withLookupAsync(targetName) { tgt =>
        withNewScanJob(tgt, "ManualScan") { job =>
          bucketScanner ! BucketScanner.PerformDeletionScan(tgt, thenScanForNew = true, Some(job))
          Ok(GenericErrorResponse("ok", "scan started").asJson)
        }
      }
    }
  }

  def manualTriggerAdditionScan(targetName:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) { maybeUserProfile=>
      withLookupAsync(targetName) { tgt =>
        withNewScanJob(tgt,"AdditionScan") { job =>
          bucketScanner ! BucketScanner.PerformTargetScan(tgt, Some(job))
          Ok(GenericErrorResponse("ok", "scan started").asJson)
        }
      }
    }
  }

  def manualTriggerDeletionScan(targetName:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {  maybeUserProfile=>
      withLookupAsync(targetName) { tgt =>
        withNewScanJob(tgt, "DeletionScan") { job =>
          bucketScanner ! BucketScanner.PerformDeletionScan(tgt, maybeJob=Some(job))
          Ok(GenericErrorResponse("ok", "scan started").asJson)
        }
      }
    }
  }

  def scanForLegacyProxies(targetName:String) = APIAuthAction { request=>
    adminsOnlySync(request) {
      withLookup(targetName) { tgt =>
        proxyScanner ! LegacyProxiesScanner.ScanBucket(tgt)
        Ok(GenericErrorResponse("ok", "scan started").asJson)
      }
    }
  }

  def genProxies(targetName:String) = APIAuthAction { request=>
    adminsOnlySync(request) {
      withLookup(targetName) { tgt =>
        bulkThumbnailer ! new BulkThumbnailer.DoThumbnails(tgt)
        Ok(GenericErrorResponse("ok", "proxy run started").asJson)
      }
    }
  }

  /**
    * ask the proxy framework to validate this configuration.
    * @param targetName
    * @return
    */
  def initiateCheckJob(targetName:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) { maybeUserProfile=>
      withLookupAsync(targetName){ tgt =>
        proxyGenerators.requestCheckJob(tgt.bucketName, tgt.proxyBucket, tgt.region).map({
          case Left(err) =>
            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
          case Right(jobId) =>
            val updatedJobIds = tgt.pendingJobIds match {
              case Some(existingSeq) => existingSeq ++ Seq(jobId.toString)
              case None => Seq(jobId.toString)
            }
            val updatedTarget = tgt.copy(pendingJobIds = Some(updatedJobIds))
            scanTargetDAO.put(updatedTarget) match {
              case Success(record) =>
                Ok(ObjectCreatedResponse("ok", "jobId", jobId).asJson)
              case Failure(err) =>
                InternalServerError(GenericErrorResponse("error", err.toString).asJson)
            }
        })
      }
    }
  }

  def createPipelines(targetName:String, force:Boolean) = APIAuthAction.async { request=>
    adminsOnlyAsync(request){ maybeUserProfile=>
      withLookupAsync(targetName){ tgt =>
        proxyGenerators.requestPipelineCreate(tgt.bucketName, tgt.proxyBucket, tgt.region, force).map({
          case Left(err)=>
            InternalServerError(GenericErrorResponse("error",err).asJson)
          case Right(jobId)=>
            val updatedScanTarget = tgt.withAnotherPendingJob(jobId)
            scanTargetDAO.put(updatedScanTarget) match {
              case Success(scanTarget)=>Ok(ObjectCreatedResponse("ok","job",jobId).asJson)
              case Failure(err)=>InternalServerError(GenericErrorResponse("error", err.toString).asJson)
            }
        })
      }
    }
  }
}
