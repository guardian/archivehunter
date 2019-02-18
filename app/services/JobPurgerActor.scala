package services

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import akka.actor.{Actor, ActorRef, ActorSystem, Status}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import akka.stream.scaladsl.{Flow, Keep, Sink}
import com.gu.scanamo.DynamoFormat
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, ScanRequest}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobModelDAO, JobStatus, SourceType}
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.collection.JavaConverters._
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
  case class CheckMaybePurge(entry: Map[String,AttributeValue], maybePurgeTime:Option[ZonedDateTime]=None) extends JPMsg
}

class JobPurgerActor @Inject() (config:Configuration, ddbClientMgr:DynamoClientManager, jobModelDAO: JobModelDAO)(implicit system:ActorSystem) extends Actor{
  import JobPurgerActor._
  import akka.stream.alpakka.dynamodb.scaladsl.DynamoImplicits._
  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.dispatcher
  implicit val mat:Materializer = ActorMaterializer.create(system)

  /**
    * this provides the actor to send CheckMaybePurge message to. Included like this to make testing easier.
    */
  protected val purgerRef:ActorRef = self

  override def receive: Receive = {
    case CheckMaybePurge(entry, maybePurgeTime)=>
      val purgeAmount = config.getOptional[Int]("jobs.purgeAfter").getOrElse(30)
      val purgeInvalid = config.getOptional[Boolean]("jobs.purgeInvalid").getOrElse(false)
      val job = JobModel(entry("jobId").getS,entry("jobType").getS,
        entry.get("startedAt").flatMap(x=>Option(x.getS)).map(timeString=>ZonedDateTime.parse(timeString, DateTimeFormatter.ISO_DATE_TIME)),
        entry.get("completedAt").flatMap(x=>Option(x.getS)).map(timeString=>ZonedDateTime.parse(timeString, DateTimeFormatter.ISO_DATE_TIME)),
        JobStatus.withName(entry("jobStatus").getS),
        entry.get("log").flatMap(x=>Option(x.getS)),
        entry("sourceId").getS,
        None, //we don't need transcodeInfo here
        SourceType.withName(entry("sourceType").getS),
        entry.get("lastUpdatedTS").flatMap(x=>Option(x.getS.toLong))
      )

      val purgeTime = maybePurgeTime match {
        case Some(t)=>t
        case None=>ZonedDateTime.now().minusDays(purgeAmount)
      }

      val originalSender = sender()

      job.startedAt match {
        case Some(startingTime)=>
          if(startingTime.isBefore(purgeTime)){
            jobModelDAO.deleteJob(job.jobId).onComplete({
              case Success(result)=>
                originalSender ! Status.Success
                logger.info(s"Deleted job ${job.jobId}, consumed ${result.getConsumedCapacity.getCapacityUnits} capacity units")
              case Failure(err)=>
                originalSender ! Status.Failure
                logger.error(s"Unable to delete job: ", err)
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
              case Success(result)=>
                originalSender ! Status.Success
                logger.info(s"Deleted job ${job.jobId}, consumed ${result.getConsumedCapacity.getCapacityUnits} capacity units")
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
      val dynamoClient = ddbClientMgr.getNewAlpakkaDynamoClient()
      logger.info(s"Starting expired job scan...")
      val src = dynamoClient.source(new ScanRequest().withTableName(config.get[String]("externalData.jobTable")))

      val originalSender = sender()
      val completionFuture = src.toMat(Sink.foreach(_.getItems.asScala.foreach(entry=>purgerRef ! CheckMaybePurge(entry.asScala.toMap))))(Keep.right).run()
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
