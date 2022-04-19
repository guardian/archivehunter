package services

import java.time.temporal.{ChronoUnit, TemporalField}
import java.time.{Instant, ZoneId, ZonedDateTime}
import akka.actor.{Actor, ActorRef, ActorSystem}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3RestoreHeader
import com.theguardian.multimedia.archivehunter.common.cmn_models._

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{HeadObjectResponse, RestoreObjectRequest, RestoreRequest, StorageClass}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

object GlacierRestoreActor {
  trait GRMsg

  /**
    * public messages to send
    * @param bucket
    * @param filePath
    */
  case class InitiateRestore(entry:ArchiveEntry, lbEntry:LightboxEntry, expiration:Option[Int]) extends GRMsg
  case class InitiateRestoreBasic(entry:ArchiveEntry, expiration:Option[Int]) extends GRMsg
  case class CheckRestoreStatus(lbEntry:LightboxEntry) extends GRMsg
  case class CheckRestoreStatusBasic(archiveEntry: ArchiveEntry) extends GRMsg

  /* private internal messages */
  case class InternalCheckRestoreStatus(lbEntry:Option[LightboxEntry], entry:ArchiveEntry, jobsList:Option[List[JobModel]], originalSender:ActorRef)

  /* reply messages to expect */
  case object RestoreSuccess extends GRMsg
  case class RestoreFailure(err:Throwable) extends GRMsg

  case class NotInArchive(entry:ArchiveEntry) extends GRMsg
  case class RestoreNotRequested(entry:ArchiveEntry) extends GRMsg
  case class RestoreInProgress(entry:ArchiveEntry) extends GRMsg
  case class RestoreExpired(entry:ArchiveEntry) extends GRMsg
  case class RestoreCompleted(entry:ArchiveEntry, expiresAt:ZonedDateTime) extends GRMsg
  case class ItemLost(entry:ArchiveEntry) extends GRMsg
}

@Singleton
class GlacierRestoreActor @Inject() (config:Configuration, esClientMgr:ESClientManager, s3ClientMgr:S3ClientManager,
                                     jobModelDAO: JobModelDAO, lbEntryDAO:LightboxEntryDAO, system:ActorSystem) extends Actor {
  import GlacierRestoreActor._
  import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._

  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.getDispatcher

  val defaultExpiry = config.getOptional[Int]("archive.restoresExpireAfter").getOrElse(3)
  logger.info(s"Glacier restores will expire after $defaultExpiry days")
  private val indexer = new Indexer(config.get[String]("externalData.indexName"))
  implicit val esClient = esClientMgr.getClient()

  def updateLightbox(lbEntry:LightboxEntry, availableUntil:Option[ZonedDateTime]=None,error:Option[Throwable]=None) = {
    val newStatus = error match {
      case Some(err)=>RestoreStatus.RS_ERROR
      case None=>RestoreStatus.RS_UNDERWAY
    }

    val updatedEntry = lbEntry.copy(restoreStarted = Some(ZonedDateTime.now()),
      restoreStatus = newStatus,
      lastError = error.map(_.toString),
    )
    lbEntryDAO.put(updatedEntry)
  }

  def updateJob(jobModel: JobModel, newStatus:JobStatus.Value, newLog:Option[String]) = {
    val updatedModel = jobModel.copy(jobStatus = newStatus, log=newLog, completedAt=Some(ZonedDateTime.now()))
    jobModelDAO.putJob(updatedModel)
  }

  def updateLightboxFull(lbEntry:LightboxEntry, newStatus:RestoreStatus.Value, expiryTime:Option[ZonedDateTime]) = {
    val updatedEntry = newStatus match {
      case RestoreStatus.RS_SUCCESS=>
        lbEntry.copy(restoreStatus = newStatus, restoreCompleted = Some(ZonedDateTime.now()), availableUntil = expiryTime)
      case RestoreStatus.RS_ERROR=>
        lbEntry.copy(restoreStatus = newStatus, restoreCompleted = Some(ZonedDateTime.now()))
      case RestoreStatus.RS_UNDERWAY=>
        lbEntry.copy(restoreStatus = newStatus)
    }
    lbEntryDAO.put(updatedEntry)
  }

  private def checkStatus(result:HeadObjectResponse, entry: ArchiveEntry, lbEntry: Option[LightboxEntry], jobs:Option[List[JobModel]], originalSender:ActorRef) = {
    S3RestoreHeader(result.restore()) match {
      case Failure(_) =>
        logger.info(s"s3://${entry.bucket}/${entry.path} has not had any restore requested")
        if(result.storageClass()!=StorageClass.GLACIER){
          originalSender ! NotInArchive(entry)
        } else {
          if(jobs.isDefined) jobs.get.foreach(job=>updateJob(job, JobStatus.ST_ERROR,Some("No restore record in S3")))
          if(lbEntry.isDefined) updateLightbox(lbEntry.get,None,error=Some(new RuntimeException("No restore record in S3")))
          originalSender ! RestoreNotRequested(entry)
        }
      case Success(S3RestoreHeader(true, _)) => //restore is in progress
        logger.info(s"s3://${entry.bucket}/${entry.path} is currently under restore")
        if(jobs.isDefined) jobs.get.foreach(job=>updateJob(job, JobStatus.ST_RUNNING, None))
        if(lbEntry.isDefined) updateLightboxFull(lbEntry.get,RestoreStatus.RS_UNDERWAY,None)
        originalSender ! RestoreInProgress(entry)
      case Success(S3RestoreHeader(false, None)) => //restore not in progress, may be completed
        if(! lbEntry.map(_.restoreStatus).contains(RestoreStatus.RS_ERROR) && ! lbEntry.map(_.restoreStatus).contains(RestoreStatus.RS_SUCCESS)) {
          updateLightbox(lbEntry.get, None, Some(new RuntimeException("Item has already expired")))
        }
        originalSender ! RestoreNotRequested(entry)
      case Success(S3RestoreHeader(false, Some(expiry))) =>
        if(jobs.isDefined) jobs.get.foreach(job=>updateJob(job,JobStatus.ST_SUCCESS,None))
        if(lbEntry.isDefined) updateLightboxFull(lbEntry.get, RestoreStatus.RS_SUCCESS, Some(expiry))
        originalSender ! RestoreCompleted(entry, expiry)
      }
    }

  private def initiateRestore(bucket:String,  key:String, maybeVersion:Option[String], maybeExpiry:Option[Int])(implicit client:S3Client) = {
    val initialRequest = RestoreObjectRequest.builder().bucket(bucket).key(key)
    val withExp = maybeExpiry match {
      case Some(expiry)=>
        val rs = RestoreRequest.builder().days(expiry).build()
        initialRequest.restoreRequest(rs)
      case None=>
        val rs = RestoreRequest.builder().days(defaultExpiry).build()
        initialRequest.restoreRequest(rs)
    }
    val withVersion = maybeVersion match {
      case Some(ver)=>
        withExp.versionId(ver)
      case None=>
        withExp
    }
    Try { client.restoreObject(withVersion.build()) }
  }

  override def receive: Receive = {
    case InternalCheckRestoreStatus(lbEntry, entry, jobs, originalSender)=>
      logger.info(s"Checking restore status for s3://${entry.bucket}/${entry.path}")
      logger.info(s"From lightbox entry $lbEntry")
      logger.info(s"With jobs $jobs")

      implicit val s3client = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"), entry.region.map(Region.of))
      s3client.getObjectMetadata(entry.bucket, entry.path,None) match {
        case Success(result)=>checkStatus(result, entry, lbEntry, jobs, originalSender)
        case Failure(s3err:com.amazonaws.services.s3.model.AmazonS3Exception)=>
          if(s3err.getStatusCode==404) {
            logger.warn(s"Registered item s3://${entry.bucket}/${entry.path} does not exist any more!")
            if(entry.beenDeleted) {
              originalSender ! ItemLost(entry)
            } else {
              val updatedEntry = entry.copy(beenDeleted = true)
              indexer.indexSingleItem(updatedEntry, Some(updatedEntry.id)).onComplete({
                case Success(Right(_))=>
                  logger.info(s"Item for s3://${entry.bucket}/${entry.path} marked as deleted")
                case Success(Left(indexerErr))=>
                  logger.error(s"Could not mark s3://${entry.bucket}/${entry.path} as deleted: $indexerErr")
                case Failure(err)=>
                  logger.error(s"Could not mark item for s3://${entry.bucket}/${entry.path} as deleted: ${err.getMessage}", err)
              })
              originalSender ! ItemLost(updatedEntry)
            }
          } else {
            logger.warn(s"Could not check restore status due to an s3 error s3://${entry.bucket}/${entry.path}: ${s3err.getMessage}", s3err)
            originalSender ! RestoreFailure(s3err)
          }
        case Failure(err)=>
          logger.warn(s"Could not check restore status for s3://${entry.bucket}/${entry.path}: ${err.getMessage}", err)
          originalSender ! RestoreFailure(err)
      }

    case CheckRestoreStatusBasic(archiveEntry)=>
      self ! InternalCheckRestoreStatus(None, archiveEntry, None, sender())

    case CheckRestoreStatus(lbEntry)=>
      val originalSender = sender()
      indexer.getById(lbEntry.fileId).map(entry=>{
        jobModelDAO.jobsForSource(entry.id).map(jobResults => {
          val failures = jobResults.collect({ case Left(err) => err })
          if (failures.nonEmpty) {
            logger.error("Could not retrieve jobs records: ")
            failures.foreach(err => logger.error(err.toString))
            originalSender ! RestoreFailure(new RuntimeException("could not retrieve job records"))
          } else {
            val jobs = jobResults.collect({ case Right(x) => x })
              .filter(_.jobType == "RESTORE")
              .filter(_.jobStatus != JobStatus.ST_ERROR)
              .filter(_.jobStatus != JobStatus.ST_SUCCESS)
            self ! InternalCheckRestoreStatus(Some(lbEntry), entry, Some(jobs), originalSender)
          }
        })
      })

    case InitiateRestoreBasic(entry, maybeExpiry)=>
      implicit val s3client = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"), entry.region.map(Region.of))
      val inst = Instant.now().plus(maybeExpiry.getOrElse(defaultExpiry).toLong, ChronoUnit.DAYS)
      val willExpire = ZonedDateTime.ofInstant(inst,ZoneId.systemDefault())

      val newJob = JobModel.newJob("RESTORE",entry.id,SourceType.SRC_MEDIA)

      val originalSender = sender()

      //completion is detected by the inputLambda, and the job status will be updated there.
      jobModelDAO.putJob(newJob).onComplete({
        case Success(_)=>
          logger.debug(s"${entry.location}: initiating restore")
          initiateRestore(entry.bucket, entry.path, entry.maybeVersion, maybeExpiry)match {
            case Success(_)=>originalSender ! RestoreInProgress
            case Failure(err)=>
              logger.error("S3 restore request failed: ", err)
              originalSender ! RestoreFailure(err)
          }
        case Failure(err)=>
          logger.error(s"Could not update job info for ${newJob.jobId} on ${entry.location}: ${err.getMessage}", err)
          originalSender ! RestoreFailure(err)
      })

    case InitiateRestore(entry, lbEntry, maybeExpiry)=>
      implicit val s3client = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"), entry.region.map(Region.of))
      val inst = Instant.now().plus(maybeExpiry.getOrElse(defaultExpiry).toLong, ChronoUnit.DAYS)
      val willExpire = ZonedDateTime.ofInstant(inst,ZoneId.systemDefault())

      val newJob = JobModel.newJob("RESTORE",entry.id,SourceType.SRC_MEDIA)

      val originalSender = sender()

      val restoreResult = for {
        _ <- jobModelDAO.putJob(newJob)
        result <- Future.fromTry{
          logger.debug(s"${entry.location}: initiating restore")
          initiateRestore(entry.bucket, entry.path, entry.maybeVersion, maybeExpiry)
        }
      } yield result

      restoreResult.onComplete({
        case Success(restoreObjectResult)=>
          logger.info(s"Restore started for ${entry.location}, requestor charged? ${restoreObjectResult.requestChargedAsString()}, output path ${restoreObjectResult.restoreOutputPath()}")
          updateLightbox(lbEntry, availableUntil=Some(willExpire)).andThen({ case _ =>
            originalSender ! RestoreSuccess
          })
        case Failure(ex:akka.stream.BufferOverflowException)=>
          logger.debug(ex.toString)
          logger.warn(s"Caught buffer overflow exception, retrying operation in 5s")
          system.scheduler.scheduleOnce(5.seconds,self,InitiateRestore(entry,lbEntry,maybeExpiry))
        case Failure(ex)=>
          logger.error(s"Could not restore ${entry.location}", ex)
          updateLightbox(lbEntry, error=Some(ex)).andThen({
            case _=>originalSender ! RestoreFailure(ex)
          })
      })

  }
}
