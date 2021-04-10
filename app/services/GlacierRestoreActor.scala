package services

import java.time.temporal.{ChronoField, ChronoUnit, TemporalAdjusters, TemporalField}
import java.time.{Instant, Period, ZoneId, ZonedDateTime}
import akka.actor.{Actor, ActorRef, ActorSystem}
import com.amazonaws.services.s3.model.{ObjectMetadata, RestoreObjectRequest}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
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
  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.getDispatcher
  lazy val s3client = s3ClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))
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

  private def checkStatus(result:ObjectMetadata, entry: ArchiveEntry, lbEntry: Option[LightboxEntry], jobs:Option[List[JobModel]], originalSender:ActorRef) = {
    val isRestoring = Option(result.getOngoingRestore).map(_.booleanValue()) //true, false or null; see https://forums.aws.amazon.com/thread.jspa?threadID=141678. Also map java boolean to scala.
    isRestoring match {
      case None =>
        logger.info(s"s3://${entry.bucket}/${entry.path} has not had any restore requested")
        if(result.getStorageClass!="GLACIER"){
          originalSender ! NotInArchive(entry)
        } else {
          if(jobs.isDefined) jobs.get.foreach(job=>updateJob(job, JobStatus.ST_ERROR,Some("No restore record in S3")))
          if(lbEntry.isDefined) updateLightbox(lbEntry.get,None,error=Some(new RuntimeException("No restore record in S3")))
          originalSender ! RestoreNotRequested(entry)
        }
      case Some(true) => //restore is in progress
        logger.info(s"s3://${entry.bucket}/${entry.path} is currently under restore")
        if(jobs.isDefined) jobs.get.foreach(job=>updateJob(job, JobStatus.ST_RUNNING, None))
        if(lbEntry.isDefined) updateLightboxFull(lbEntry.get,RestoreStatus.RS_UNDERWAY,None)
        originalSender ! RestoreInProgress(entry)
      case Some(false) => //restore not in progress, may be completed
        Option(result.getRestoreExpirationTime)
          .map(date => ZonedDateTime.ofInstant(date.toInstant, ZoneId.systemDefault())) match {
          case None => //no restore time, so it's gone back again
            if(lbEntry.isDefined) {
              if (lbEntry.get.restoreStatus != RestoreStatus.RS_ERROR && lbEntry.get.restoreStatus != RestoreStatus.RS_SUCCESS) {
                updateLightbox(lbEntry.get, None, Some(new RuntimeException("Item has already expired")))
              }
            }
            originalSender ! RestoreNotRequested(entry)
          case Some(expiry) =>
            if(jobs.isDefined) jobs.get.foreach(job=>updateJob(job,JobStatus.ST_SUCCESS,None))
            if(lbEntry.isDefined) updateLightboxFull(lbEntry.get, RestoreStatus.RS_SUCCESS, Some(expiry))
            originalSender ! RestoreCompleted(entry, expiry)
        }
    }
  }

  override def receive: Receive = {
    case InternalCheckRestoreStatus(lbEntry, entry, jobs, originalSender)=>
      logger.info(s"Checking restore status for s3://${entry.bucket}/${entry.path}")
      logger.info(s"From lightbox entry $lbEntry")
      logger.info(s"With jobs $jobs")

      Try {
        s3client.getObjectMetadata(entry.bucket, entry.path)
      } match {
        case Success(result)=>checkStatus(result, entry, lbEntry, jobs, originalSender)
        case Failure(s3err:com.amazonaws.services.s3.model.AmazonS3Exception)=>
          if(s3err.getStatusCode==404) {
            logger.warn(s"Registered item s3://${entry.bucket}/${entry.path} does not exist any more!")
            if(!entry.beenDeleted) {
              val updatedEntry = entry.copy(beenDeleted = true)
              indexer.indexSingleItem(updatedEntry, Some(updatedEntry.id)).onComplete({
                case Success(Right(_))=>
                  logger.info(s"Item for s3://${entry.bucket}/${entry.path} marked as deleted")
                case Success(Left(indexerErr))=>
                  logger.error(s"Could not mark s3://${entry.bucket}/${entry.path} as deleted: $indexerErr")
                case Failure(err)=>
                  logger.error(s"Could not mark item for s3://${entry.bucket}/${entry.path} as deleted: ${err.getMessage}", err)
              })
            }

            originalSender ! ItemLost(entry)
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
            sender ! RestoreFailure(new RuntimeException("could not retrieve job records"))
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
      val rq = new RestoreObjectRequest(entry.bucket, entry.path, maybeExpiry.getOrElse(defaultExpiry))
      val inst = Instant.now().plus(maybeExpiry.getOrElse(defaultExpiry).toLong, ChronoUnit.DAYS)
      val willExpire = ZonedDateTime.ofInstant(inst,ZoneId.systemDefault())

      val newJob = JobModel.newJob("RESTORE",entry.id,SourceType.SRC_MEDIA)

      val originalSender = sender()

      //completion is detected by the inputLambda, and the job status will be updated there.
      jobModelDAO.putJob(newJob).onComplete({
        case Success(None)=>
          logger.debug(s"${entry.location}: initiating restore")
          Try { s3client.restoreObjectV2(rq) } match {
            case Success(_)=>originalSender ! RestoreInProgress
            case Failure(err)=>
              logger.error("S3 restore request failed: ", err)
              originalSender ! RestoreFailure(err)
          }
        case Success(Some(Right(record)))=>
          logger.debug(s"${entry.location}: initiating restore")
          Try { s3client.restoreObjectV2(rq) } match {
            case Success(_)=>originalSender ! RestoreInProgress
            case Failure(err)=>
              logger.error("S3 restore request failed: ", err)
              originalSender ! RestoreFailure(err)
          }
        case Success(Some(Left(error)))=>
          logger.error(s"Could not update job info for ${newJob.jobId} on ${entry.location}: $error")
          originalSender ! RestoreFailure(new RuntimeException("Could not update job"))
        case Failure(err)=>
          logger.error(s"putJob crashed", err)
          originalSender ! RestoreFailure(err)
      })

    case InitiateRestore(entry, lbEntry, maybeExpiry)=>
      val rq = new RestoreObjectRequest(entry.bucket, entry.path, maybeExpiry.getOrElse(defaultExpiry))
      val inst = Instant.now().plus(maybeExpiry.getOrElse(defaultExpiry).toLong, ChronoUnit.DAYS)
      val willExpire = ZonedDateTime.ofInstant(inst,ZoneId.systemDefault())

      val newJob = JobModel.newJob("RESTORE",entry.id,SourceType.SRC_MEDIA)

      val originalSender = sender()

      //completion is detected by the inputLambda, and the job status will be updated there.
      jobModelDAO.putJob(newJob).map({
        case None=>
          logger.debug(s"${entry.location}: initiating restore")
          Try { s3client.restoreObjectV2(rq) }
        case Some(Right(record))=>
          logger.debug(s"${entry.location}: initiating restore")
          Try { s3client.restoreObjectV2(rq) }
        case Some(Left(error))=>
          logger.error(s"Could not update job info for ${newJob.jobId} on ${entry.location}: $error")
          val err = new RuntimeException(error.toString)
          Failure(err)
      }).map({
        case Success(restoreObjectResult)=>
          logger.info(s"Restore started for ${entry.location}, requestor charged? ${restoreObjectResult.isRequesterCharged}, output path ${restoreObjectResult.getRestoreOutputPath}")
          updateLightbox(lbEntry, availableUntil=Some(willExpire)).andThen({ case _ =>
            originalSender ! RestoreSuccess
          })

        case Failure(ex)=>
          logger.error(s"Could not restore ${entry.location}", ex)
          updateLightbox(lbEntry, error=Some(ex)).andThen({
            case _=>originalSender ! RestoreFailure(ex)
          })
      }).recover({
        case ex:akka.stream.BufferOverflowException=>
          logger.debug(ex.toString)
          logger.warn(s"Caught buffer overflow exception, retrying operation in 5s")
          system.scheduler.scheduleOnce(5.seconds,self,InitiateRestore(entry,lbEntry,maybeExpiry))
        case ex:Throwable=>
          logger.error(s"Unexpected exception while initiating restore of ${entry.location}: ", ex)
      })

  }
}
