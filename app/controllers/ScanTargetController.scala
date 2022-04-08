package controllers

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import auth.{BearerTokenAuth, Security}
import org.scanamo._
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder

import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.mvc._
import io.circe.generic.auto._
import io.circe.syntax._
import play.api.libs.circe.Circe
import responses.{CheckNotificationResponse, GenericErrorResponse, ObjectCreatedResponse, ObjectGetResponse, ObjectListResponse}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import org.slf4j.LoggerFactory
import play.api.cache.SyncCacheApi
import services.{BucketNotificationConfigurations, BucketScanner, BulkThumbnailer, LegacyProxiesScanner}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

@Singleton
class ScanTargetController @Inject() (@Named("bucketScannerActor") bucketScanner:ActorRef,
                                      @Named("legacyProxiesScannerActor") proxyScanner:ActorRef,
                                      @Named("bulkThumbnailerActor") bulkThumbnailer: ActorRef,
                                      override val config:Configuration,
                                      override val controllerComponents:ControllerComponents,
                                      override val bearerTokenAuth:BearerTokenAuth,
                                      override val cache:SyncCacheApi,
                                      ddbClientMgr:DynamoClientManager,
                                      proxyGenerators:ProxyGenerators,
                                      scanTargetDAO:ScanTargetDAO,
                                      jobModelDAO:JobModelDAO,
                                      bucketNotifications:BucketNotificationConfigurations)
                                     (implicit system:ActorSystem, mat:Materializer)
  extends AbstractController(controllerComponents) with Security with Circe with ZonedDateTimeEncoder with ZonedTimeFormat with JobModelEncoder {
  override protected val logger=LoggerFactory.getLogger(getClass)

  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))

  private val profileName = config.getOptional[String]("externalData.awsProfile")

  private val scanamo = Scanamo(ddbClientMgr.getNewDynamoClient(profileName))
  private val scanamoAlpakka = ScanamoAlpakka(ddbClientMgr.getNewAsyncDynamoClient(profileName))

  def newTarget = IsAdmin(circe.json[ScanTarget]) { _=> request=>
    try {
      scanamo.exec(table.put(request.body))
      Ok(ObjectCreatedResponse[String]("created","scan_target",request.body.bucketName).asJson)
    } catch {
      case err:Throwable=>
        logger.error(s"Can't create scan target ${request.body.bucketName}: ${err.getMessage}", err)
        InternalServerError(GenericErrorResponse("error",err.getMessage).asJson)
    }
  }

  def removeTarget(targetName:String) = IsAdmin { _=> request=>
    try {
      scanamo.exec(table.delete("bucketName" === targetName))
      Ok(ObjectCreatedResponse[String]("deleted", "scan_target", targetName).asJson)
    } catch {
      case err:Throwable=>
        logger.error(s"Can't create scan target $targetName: ${err.getMessage}", err)
        InternalServerError(GenericErrorResponse("error",err.getMessage).asJson)
    }
  }

  def get(targetName:String) = IsAdmin { _=> request=>
    scanamo.exec(table.get("bucketName"===targetName)).map({
      case Left(err) =>
        InternalServerError(GenericErrorResponse("database_error", err.toString).asJson)
      case Right(result) =>
        Ok(ObjectGetResponse[ScanTarget]("ok", "scan_target", result).asJson)
    }).getOrElse(NotFound(ObjectCreatedResponse[String]("not_found", "scan_target", targetName).asJson))
  }

  def listScanTargets = IsAdminAsync { _=> request=>
    scanTargetDAO.allScanTargets()
      .map({ result =>
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

  private def withLookup(targetName:String)(block: ScanTarget=>Result) = scanamo
    .exec(table.get("bucketName"===targetName ))
    .map({
      case Left(error)=>
        InternalServerError(GenericErrorResponse("error", error.toString).asJson)
      case Right(tgt)=>
        block(tgt)
    })
    .getOrElse(NotFound(ObjectCreatedResponse[String]("not_found","scan_target",targetName).asJson))

  private def withLookupAsync(targetName:String)(block: ScanTarget=>Future[Result]) = scanamo
    .exec(table.get("bucketName"===targetName ))
    .map({
      case Left(error)=>
        Future(InternalServerError(GenericErrorResponse("error", error.toString).asJson))
      case Right(tgt)=>
        block(tgt)
    })
    .getOrElse(Future(NotFound(ObjectCreatedResponse[String]("not_found","scan_target",targetName).asJson)))

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

    jobModelDAO
      .putJob(job)
      .flatMap(_=>{
        val updatedTarget = tgt.withAnotherPendingJob(job.jobId)
        Future.fromTry(scanTargetDAO.put(updatedTarget))
      })
      .map(_=>block(job))
      .recover({
        case err:Throwable=>
          logger.error(s"Could not create and save new scan job: ${err.getMessage}", err)
          InternalServerError(GenericErrorResponse("db_error","Could not create and save scan job, see logs").asJson)
      })
  }

  def manualTrigger(targetName:String) = IsAdminAsync { _=> request=>
    withLookupAsync(targetName) { tgt =>
      withNewScanJob(tgt, "ManualScan") { job =>
        bucketScanner ! BucketScanner.PerformDeletionScan(tgt, thenScanForNew = true, Some(job))
        Ok(GenericErrorResponse("ok", "scan started").asJson)
      }
    }
  }

  def manualTriggerAdditionScan(targetName:String) = IsAdminAsync { _=> request=>
    withLookupAsync(targetName) { tgt =>
      withNewScanJob(tgt,"AdditionScan") { job =>
        bucketScanner ! BucketScanner.PerformTargetScan(tgt, Some(job))
        Ok(GenericErrorResponse("ok", "scan started").asJson)
      }
    }
  }

  def manualTriggerDeletionScan(targetName:String) = IsAdminAsync { _=> request=>
    withLookupAsync(targetName) { tgt =>
      withNewScanJob(tgt, "DeletionScan") { job =>
        bucketScanner ! BucketScanner.PerformDeletionScan(tgt, maybeJob=Some(job))
        Ok(GenericErrorResponse("ok", "scan started").asJson)
      }
    }
  }

  def scanForLegacyProxies(targetName:String) = IsAdmin { _=> request=>
    withLookup(targetName) { tgt =>
      proxyScanner ! LegacyProxiesScanner.ScanBucket(tgt)
      Ok(GenericErrorResponse("ok", "scan started").asJson)
    }
  }

  def genProxies(targetName:String) = IsAdmin { _=> request=>
    withLookup(targetName) { tgt =>
      bulkThumbnailer ! new BulkThumbnailer.DoThumbnails(tgt)
      Ok(GenericErrorResponse("ok", "proxy run started").asJson)
    }
  }

  /**
    * ask the proxy framework to validate this configuration.
    * @param targetName
    * @return
    */
  def initiateCheckJob(targetName:String) = IsAdminAsync { _=> request=>
    withLookupAsync(targetName){ tgt =>
      proxyGenerators.requestCheckJob(tgt.bucketName, tgt.proxyBucket, tgt.region).map({
        case Left(err) =>
          InternalServerError(GenericErrorResponse("error", err).asJson)
        case Right(jobId) =>
          val updatedJobIds = tgt.pendingJobIds match {
            case Some(existingSeq) => existingSeq ++ Seq(jobId)
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

  def createPipelines(targetName:String, force:Boolean) = IsAdminAsync { _=> _=>
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

  def fixNotificationConfiguration(targetName:String) = notificationConfiguration(targetName, true)
  def checkNotificationConfiguration(targetName:String) = notificationConfiguration(targetName, false)

  def notificationConfiguration(targetName:String, shouldUpdate:Boolean) = IsAdmin { _=> _=>
    withLookup(targetName) { tgt=>
      bucketNotifications.verifyNotificationSetup(tgt.bucketName, Some(tgt.region), shouldUpdate) match {
        case Success((updatesRequired, didUpdate))=>
          Ok(CheckNotificationResponse("ok",updatesRequired,didUpdate).asJson)
        case Failure(err)=>
          logger.error(s"Could not check notification configuration on target $targetName (${tgt.bucketName} in ${tgt.region}): ${err.getClass.getCanonicalName} ${err.getMessage}", err)
          InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      }
    }
  }
}
