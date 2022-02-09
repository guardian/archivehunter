package services

import java.time.ZonedDateTime
import akka.actor.{Actor, ActorRef, ActorSystem, Status}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobModelDAO, JobModelEncoder}
import org.scanamo._
import org.scanamo.syntax._
import org.scanamo.generic.auto._

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object JobPurgerActor {
  trait JPMsg

  /**
    * public message that starts the purge process
    */
  case object StartJobPurge extends JPMsg

  /**
    * internal message to test the given job entry (provided as a raw map) and delete it if necessary
    * @param entry Map of [String,AttributeValue] providing the job data to test
    * @param maybePurgeTime Optionally override the purging time; for testing only. Normally this is None and the purge
    *                       time is given by (currentTime - config("jobs.purgeAfter"))
    */
  case class CheckMaybePurge(entry: JobModel, maybePurgeTime:Option[ZonedDateTime]=None) extends JPMsg
}

@Singleton
class JobPurgerActor @Inject() (config:Configuration, ddbClientMgr:DynamoClientManager, jobModelDAO: JobModelDAO)(implicit system:ActorSystem, mat:Materializer)
  extends Actor with JobModelEncoder {
  import JobPurgerActor._
  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.dispatcher

  val scanamoAlpakka = ScanamoAlpakka(ddbClientMgr.getNewAsyncDynamoClient())
  val tableName = config.get[String]("externalData.jobTable")

  protected def makeScanSource() = Source.fromGraph(scanamoAlpakka.exec(Table[JobModel](tableName).scan()))
  /**
    * this provides the actor to send CheckMaybePurge message to. Included like this to make testing easier.
    */
  protected val purgerRef:ActorRef = self

  override def receive: Receive = {
    case CheckMaybePurge(job, maybePurgeTime)=>
      val purgeAmount = config.getOptional[Int]("jobs.purgeAfter").getOrElse(30)
      val purgeInvalid = config.getOptional[Boolean]("jobs.purgeInvalid").getOrElse(false)


      val purgeTime = maybePurgeTime match {
        case Some(t)=>t
        case None=>ZonedDateTime.now().minusDays(purgeAmount)
      }

      val originalSender = sender()

      job.startedAt match {
        case Some(startingTime)=>
          if(startingTime.isBefore(purgeTime)){
            jobModelDAO.deleteJob(job.jobId).onComplete({
              case Success(_)=>
                originalSender ! Status.Success
                logger.info(s"Deleted job ${job.jobId}")
              case Failure(err)=>
                originalSender ! Status.Failure
                logger.error(s"Unable to delete job $job: ${err.getMessage}", err)
            })
          } else {
            logger.debug(s"Not purging job ${job.jobId}, startedTime was ${job.startedAt}")
            originalSender ! Status.Success
          }
        case None=>
          logger.warn(s"Job ${job.jobId} has no starting time?")
          if(purgeInvalid){
            logger.info(s"Deleting invalid job ${job.jobId}")
            jobModelDAO.deleteJob(job.jobId).onComplete({
              case Success(_)=>
                originalSender ! Status.Success
                logger.info(s"Deleted job ${job.jobId}")
              case Failure(err)=>
                originalSender ! Status.Failure
                logger.error(s"Unable to delete job: ", err)
            })
          } else {
            originalSender ! Status.Success
            logger.info(s"Not deleting invalid jobs. Set jobs.purgeInvalid to true to enable this.")
          }
      }

    case StartJobPurge=>
      logger.info(s"Starting expired job scan...")

      val src = makeScanSource()

      val originalSender = sender()
      val completionFuture = src.map(results=>{
        results.foreach({
          case Left(err)=>
            logger.error(s"Could not look up jobs to purge: ${err.toString}")
          case Right(mdl)=>
            purgerRef ! CheckMaybePurge(mdl)
        })
      }).toMat(Sink.ignore)(Keep.right).run()

      completionFuture.onComplete({
        case Success(_)=>
          logger.info(s"Expired job scan completed successfully")
          originalSender ! Status.Success
        case Failure(err)=>
          logger.error(s"Could not complete expired job scan: ", err)
          originalSender ! Status.Failure
      })
  }
}
