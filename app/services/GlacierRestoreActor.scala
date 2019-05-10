package services

import java.time.temporal.{ChronoField, ChronoUnit, TemporalAdjusters, TemporalField}
import java.time.{Instant, Period, ZoneId, ZonedDateTime}

import akka.actor.{Actor, ActorRef, ActorSystem}
import com.amazonaws.services.s3.model.RestoreObjectRequest
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext

object GlacierRestoreActor {
  trait GRMsg

  /**
    * public messages to send
    * @param bucket
    * @param filePath
    */
  case class InitiateRestore(entry:ArchiveEntry, lbEntry:LightboxEntry, expiration:Option[Int]) extends GRMsg
  case class CheckRestoreStatus(lbEntry:LightboxEntry) extends GRMsg

  /* private internal messages */
  case class InternalCheckRestoreStatus(lbEntry:LightboxEntry, entry:ArchiveEntry, jobsList:List[JobModel], originalSender:ActorRef)

  /* reply messages to expect */
  case object RestoreSuccess extends GRMsg
  case class RestoreFailure(err:Throwable) extends GRMsg

  case class NotInArchive(entry:ArchiveEntry) extends GRMsg
  case class RestoreNotRequested(entry:ArchiveEntry) extends GRMsg
  case class RestoreInProgress(entry:ArchiveEntry) extends GRMsg
  case class RestoreExpired(entry:ArchiveEntry) extends GRMsg
  case class RestoreCompleted(entry:ArchiveEntry, expiresAt:ZonedDateTime) extends GRMsg
}

@Singleton
class GlacierRestoreActor @Inject() (config:Configuration, esClientMgr:ESClientManager, s3ClientMgr:S3ClientManager,
                                     jobModelDAO: JobModelDAO, lbEntryDAO:LightboxEntryDAO, system:ActorSystem) extends Actor {
  import GlacierRestoreActor._
  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.getDispatcher
  val s3client = s3ClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))
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

  override def receive: Receive = {
    case InternalCheckRestoreStatus(lbEntry, entry, jobs, originalSender)=>
      logger.info(s"Checking restore status for s3://${entry.bucket}/${entry.path}")
      logger.info(s"From lightbox entry $lbEntry")
      logger.info(s"With jobs $jobs")

      val result = s3client.getObjectMetadata(entry.bucket, entry.path)
      val isRestoring = Option(result.getOngoingRestore).map(_.booleanValue()) //true, false or null; see https://forums.aws.amazon.com/thread.jspa?threadID=141678. Also map java boolean to scala.
      isRestoring match {
        case None =>
          logger.info(s"s3://${entry.bucket}/${entry.path} has not had any restore requested")
          if(result.getStorageClass!="GLACIER"){
            originalSender ! NotInArchive(entry)
          } else {
            jobs.foreach(job=>updateJob(job, JobStatus.ST_ERROR,Some("No restore record in S3")))
            updateLightbox(lbEntry,None,error=Some(new RuntimeException("No restore record in S3")))
            originalSender ! RestoreNotRequested(entry)
          }
        case Some(true) => //restore is in progress
          logger.info(s"s3://${entry.bucket}/${entry.path} is currently under restore")
          jobs.foreach(job=>updateJob(job, JobStatus.ST_RUNNING, None))
          updateLightboxFull(lbEntry,RestoreStatus.RS_UNDERWAY,None)
          originalSender ! RestoreInProgress(entry)
        case Some(false) => //restore not in progress, may be completed
          Option(result.getRestoreExpirationTime)
            .map(date => ZonedDateTime.ofInstant(date.toInstant, ZoneId.systemDefault())) match {
            case None => //no restore time, so it's gone back again
              if(lbEntry.restoreStatus!=RestoreStatus.RS_ERROR && lbEntry.restoreStatus!=RestoreStatus.RS_SUCCESS){
                updateLightbox(lbEntry,None, Some(new RuntimeException("Item has already expired")))
              }
              originalSender ! RestoreNotRequested(entry)
            case Some(expiry) =>
              jobs.foreach(job=>updateJob(job,JobStatus.ST_SUCCESS,None))
              updateLightboxFull(lbEntry, RestoreStatus.RS_SUCCESS, Some(expiry))
              originalSender ! RestoreCompleted(entry, expiry)
          }
      }

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
            self ! InternalCheckRestoreStatus(lbEntry, entry, jobs, originalSender)
          }
        })
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
          try {
            val result = s3client.restoreObjectV2(rq)
            updateLightbox(lbEntry, availableUntil=Some(willExpire)).andThen({ case _ =>
              originalSender ! RestoreSuccess
            })
          } catch {
            case ex:Throwable=>
              updateLightbox(lbEntry, error=Some(ex)).andThen({
                case _=>originalSender ! RestoreFailure(ex)
              })
          }
        case Some(Right(record))=>
          try {
            val result = s3client.restoreObjectV2(rq)
            updateLightbox(lbEntry, availableUntil=Some(willExpire)).andThen({ case _ =>
              originalSender ! RestoreSuccess
            })
          } catch {
            case ex:Throwable=>
              updateLightbox(lbEntry, error=Some(ex)).andThen({
                case _=>originalSender ! RestoreFailure(ex)
              })
          }
        case Some(Left(error))=>
          val err = new RuntimeException(error.toString)
          updateLightbox(lbEntry, error=Some(err)).andThen({
            case _=>originalSender ! RestoreFailure(err)
          })
      })

  }
}
