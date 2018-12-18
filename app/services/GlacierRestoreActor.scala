package services

import java.time.temporal.{ChronoField, ChronoUnit, TemporalAdjusters, TemporalField}
import java.time.{Instant, Period, ZoneId, ZonedDateTime}

import akka.actor.{Actor, ActorSystem}
import com.amazonaws.services.s3.model.RestoreObjectRequest
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import javax.inject.Inject
import play.api.Configuration

import scala.concurrent.ExecutionContext

object GlacierRestoreActor {
  trait GRMsg

  /**
    * public messages to send
    * @param bucket
    * @param filePath
    */
  case class InitiateRestore(entry:ArchiveEntry, lbEntry:LightboxEntry, expiration:Option[Int]) extends GRMsg

  /* reply messages to expect */
  case object RestoreSuccess extends GRMsg
  case class RestoreFailure(err:Throwable) extends GRMsg
}


class GlacierRestoreActor @Inject() (config:Configuration, s3ClientMgr:S3ClientManager, jobModelDAO: JobModelDAO, lbEntryDAO:LightboxEntryDAO, system:ActorSystem) extends Actor {
  import GlacierRestoreActor._

  implicit val ec:ExecutionContext = system.getDispatcher
  val s3client = s3ClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))
  val defaultExpiry = config.getOptional[Int]("archive.restoresExpireAfter").getOrElse(3)

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

  override def receive: Receive = {
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
