package services

import java.time.ZonedDateTime
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import io.circe.generic.auto._
import javax.inject.Inject
import models.{AwsSqsMsg, JobReportNew}
import play.api.{Configuration, Logger}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ProxyFrameworkQueue extends GenericSqsActorMessages {
  trait PFQMsg extends SQSMsg

  case class HandleSuccess(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleSuccessfulProxy(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleFailure(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleRunning(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
}

class ProxyFrameworkQueue @Inject() (config: Configuration,
                                     system: ActorSystem,
                                     sqsClientManager: SQSClientManager,
                                     s3ClientMgr: S3ClientManager,
                                     dynamoClientMgr: DynamoClientManager,
                                     jobModelDAO: JobModelDAO,
                                     esClientMgr:ESClientManager
                                    )(implicit proxyLocationDAO: ProxyLocationDAO)
  extends GenericSqsActor[JobReportNew] {
  import ProxyFrameworkQueue._
  import GenericSqsActor._

  private val logger = Logger(getClass)

  override protected val sqsClient = sqsClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))

  override protected implicit val implSystem = system
  override protected implicit val mat: Materializer = ActorMaterializer.create(system)

  //override this in testing
  protected val ownRef: ActorRef = self

  override protected implicit val ec: ExecutionContext = system.dispatcher

  override protected val notificationsQueue = config.get[String]("proxyFramework.notificationsQueue")

  private implicit val s3Client = s3ClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))
  private implicit val ddbClient = dynamoClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  private implicit val esClient = esClientMgr.getClient()
  private implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))

  override def convertMessageBody(body: String) =
    AwsSqsMsg.fromJsonString(body).flatMap(snsMsg=>{
      io.circe.parser.parse(snsMsg.Message).flatMap(_.as[JobReportNew])
    })

  /**
    * looks up the ArchiveEntry for the original media associated with the given JobModel
    * @param jobDesc JobModel instance
    * @param esClient implicitly provided ElasticSearch HttpClient
    * @param indexer implicitly provided Indexer instance
    * @param ec implicitly provided ExecutionContext
    * @return a Future, containing either Left with an error string or Right with an ArchiveEntry
    */
  def thumbnailJobOriginalMedia(jobDesc:JobModel) = jobDesc.sourceType match {
    case SourceType.SRC_MEDIA=>
      indexer.getById(jobDesc.sourceId).map(result=>Right(result))
    case SourceType.SRC_PROXY=>
      Future(Left("need original media!"))
    case SourceType.SRC_THUMBNAIL=>
      Future(Left("need original media!"))
  }

  /**
    * update the proxy location in the database
    * @param proxyUri new proxy location URI
    * @param archiveEntry ArchiveEntry instance of the media that this proxy is for
    * @param proxyLocationDAO proxy location Data Access Object
    * @param s3Client implicitly provided S3 client object
    * @param ec implicitly provided execution context
    * @return a Future with either an error string or a success containing the updated record (if available)
    */
  def updateProxyRef(proxyUri:String, archiveEntry:ArchiveEntry, proxyType:ProxyType.Value) =
    ProxyLocation.fromS3(
      proxyUri=proxyUri,
      mainMediaUri=s"s3://${archiveEntry.bucket}/${archiveEntry.path}", Some(proxyType))
    .flatMap({
      case Left(err)=>
        logger.error(s"Could not get proxy location: $err")
        Future(Left(err))
      case Right(proxyLocation)=>
        logger.info("Saving proxy location...")
        proxyLocationDAO.saveProxy(proxyLocation).map({
          case None=>
            Right(None)
          case Some(Left(err))=>
            Left(err.toString)
          case Some(Right(updatedLocation))=>
            logger.info(s"Updated location: $updatedLocation")
            Right(Some(updatedLocation))
        })
    })

  override def receive: Receive = {
    /**
      * if a proxy job completed successfully, then update the proxy location table with the newly generated proxy
      */
    case HandleSuccessfulProxy(msg, jobDesc, rq, receiptHandle, originalSender)=>
      logger.info(s"Outboard process indicated job success: $msg")

      if(msg.output.isEmpty){
        logger.error(s"Message $msg logged as success but had no 'output' field!")
        originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Message logged as success but had no 'output' field!"))
      } else {
        val proxyType = jobDesc.jobType match {
          case "thumbnail"=>ProxyType.THUMBNAIL
          case "proxy"=>ProxyType.VIDEO //FIXME: need to get data from transcode framework to determine what this actually is
        }
        val proxyUpdateFuture = thumbnailJobOriginalMedia(jobDesc).flatMap({
          case Left(err) => Future(Left(err))
          case Right(archiveEntry) => updateProxyRef(msg.output.get, archiveEntry, proxyType)
        })
        proxyUpdateFuture.map({
          case Left(err) =>
            logger.error(s"Could not update proxy: $err")
            val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now), log = Some(s"Could not update proxy: $err"), jobStatus = JobStatus.ST_ERROR)
            jobModelDAO.putJob(updatedJd).onComplete({
              case Success(_) =>
                originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not update proxy: $err"))
              case Failure(dbErr) =>
                originalSender ! akka.actor.Status.Failure(dbErr)
            })
          case Right(_) =>
            val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now), jobStatus = JobStatus.ST_SUCCESS)
            jobModelDAO.putJob(updatedJd).onComplete({
              case Success(_) =>
                //only delete the message here.  This will ensure that it's retried elsewhere if we fail.
                sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
                originalSender ! akka.actor.Status.Success()
              case Failure(dbErr) =>
                originalSender ! akka.actor.Status.Failure(dbErr)
            })
        })
      }

    /**
      * route a success message to the appropriate handler
      */
    case HandleSuccess(msg, jobDesc, rq, receiptHandle, originalSender)=>
      logger.debug(s"Got success for jobDesc $jobDesc")
      jobDesc.jobType match {
        case "proxy"=>self ! HandleSuccessfulProxy(msg, jobDesc, rq, receiptHandle, originalSender)
        case "thumbnail"=>self ! HandleSuccessfulProxy(msg, jobDesc, rq, receiptHandle, originalSender)
      }

    /**
      * if a proxy job started running, update the job status in the database
      */
    case HandleRunning(msg, jobDesc, rq, receiptHandle, originalSender)=>
      val updatedJd = jobDesc.copy(jobStatus = JobStatus.ST_RUNNING)
      jobModelDAO.putJob(updatedJd).onComplete({
        case Success(Some(Left(err)))=>
          logger.error(s"Could not update job model in database: ${err.toString}")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not update job model in database: ${err.toString}"))
        case Success(_)=>
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          originalSender ! akka.actor.Status.Success()
        case Failure(err)=>
          logger.error("Could not update job model in database", err)
          originalSender ! akka.actor.Status.Failure(err)
      })

    /**
      * if a proxy job failed, then record this in the database along with any decoded log
      */
    case HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)=>
      val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now),
        jobStatus = JobStatus.ST_ERROR,
        log=msg.decodedLog.map(_.fold(
          err=>s"Could not decode job log: $err",
          logContent=>logContent
        ))
      )

      jobModelDAO.putJob(updatedJd).onComplete({
        case Success(Some(Left(err)))=>
          logger.error(s"Could not update job model in database: ${err.toString}")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not update job model in database: ${err.toString}"))
        case Success(_)=>
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          originalSender ! akka.actor.Status.Success()
        case Failure(err)=>
          logger.error("Could not update job model in database", err)
          originalSender ! akka.actor.Status.Failure(err)
      })

    case HandleDomainMessage(msg: JobReportNew, rq, receiptHandle)=>
      logger.debug(s"HandleDomainMessage: $msg")
      val originalSender=sender()
      jobModelDAO.jobForId(msg.jobId).map({
        case None =>
          logger.error(s"Could not process $msg: No job found for ${msg.jobId}")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not process $msg: No job found for ${msg.jobId}"))
        case Some(Left(err)) =>
          logger.error(s"Could not look up job for $msg: ${err.toString}")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not look up job for $msg: ${err.toString}"))
        case Some(Right(jobDesc)) =>
          logger.debug(s"Got jobDesc $jobDesc")
          msg.status match {
            case "success" => ownRef ! HandleSuccess(msg, jobDesc, rq, receiptHandle, originalSender)
            case "failure" => ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
            case "error" => ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
            case "running" => ownRef ! HandleRunning(msg, jobDesc, rq, receiptHandle, originalSender)
          }
      }).recover({
        case err:Throwable=>
          logger.error(s"Could not look up job for $msg in database", err)
      })
    case other:GenericSqsActor.SQSMsg => handleGeneric(other)
  }
}
