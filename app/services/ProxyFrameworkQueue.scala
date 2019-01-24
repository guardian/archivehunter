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
import models.{AwsSqsMsg, JobReportNew, JobReportStatus, JobReportStatusEncoder}
import play.api.{Configuration, Logger}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ProxyFrameworkQueue extends GenericSqsActorMessages {
  trait PFQMsg extends SQSMsg

  case class HandleSuccess(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleCheckSetup(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleGenericSuccess(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleTranscodingSetup(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleSuccessfulProxy(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleSuccessfulAnalyse(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleFailure(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleRunning(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
}

class ProxyFrameworkQueue @Inject() (config: Configuration,
                                     system: ActorSystem,
                                     sqsClientManager: SQSClientManager,
                                     s3ClientMgr: S3ClientManager,
                                     dynamoClientMgr: DynamoClientManager,
                                     jobModelDAO: JobModelDAO,
                                     scanTargetDAO: ScanTargetDAO,
                                     esClientMgr:ESClientManager
                                    )(implicit proxyLocationDAO: ProxyLocationDAO)
  extends GenericSqsActor[JobReportNew] with ProxyTypeEncoder with JobReportStatusEncoder with MediaMetadataEncoder {
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

  private implicit val ddbClient = dynamoClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  private implicit val esClient = esClientMgr.getClient()
  private implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))

  lazy val defaultRegion = config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1")

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
    * looks up the ArchiveEntry referenced by the job model and executes the provided block on it if the lookup succeeds.
    * If not, updates the job to a failed status and signals the sender that the operation failed, but does not delete the
    * message from the queue
    * @param msg
    * @param jobDesc
    * @param rq
    * @param receiptHandle
    * @param originalSender
    * @param block
    * @return
    */
  def withArchiveEntry(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef)(block:ArchiveEntry=>Unit) = {
    indexer.getById(jobDesc.sourceId)
      .map(entry=>block(entry))
      .recover({
        case err:Throwable=>
          logger.error("Could not look up archive entry: ", err)
          val updatedLog = jobDesc.log match {
            case Some(existingLog) => existingLog + "\n" + s"Could not look up archive entry: ${err.toString}"
            case None => s"Could not look up archive entry: ${err.toString}"
          }

          val updatedJobDesc = jobDesc.copy(jobStatus = JobStatus.ST_ERROR, log = Some(updatedLog))
          jobModelDAO.putJob(updatedJobDesc)
          ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
          originalSender ! akka.actor.Status.Failure(err)
      })
  }

  /**
    * looks up a ScanTarget waiting for this job and executes the provided block on it if the lookup succeeds.
    * if the lookup does not succeed then mark the job as failed.
    * @param msg
    * @param jobDesc
    * @param rq
    * @param receiptHandle
    * @param originalSender
    * @param block
    * @return
    */
  def withScanTarget(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef)(block:ScanTarget=>Unit) =
    scanTargetDAO.waitingForJobId(jobDesc.jobId).map({
      case Left(errList) =>
        logger.error(s"Could not find scan target: $errList")
        val updatedLog = jobDesc.log match {
          case Some(existingLog) => existingLog + "\n" + s"Database error: $errList"
          case None => s"Database error: $errList"
        }

        val updatedJobDesc = jobDesc.copy(jobStatus = JobStatus.ST_ERROR, log = Some(updatedLog))
        jobModelDAO.putJob(updatedJobDesc)
        ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
        originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not locate scan target: $errList"))
      case Right(None) =>
        logger.error(s"No scanTarget is waiting for ${jobDesc.jobId} so nothing to update")
        val updatedLog = jobDesc.log match {
          case Some(existingLog) => existingLog + "\n" + s"No scanTarget is waiting for ${jobDesc.jobId} so nothing to update"
          case None => s"No scanTarget is waiting for ${jobDesc.jobId} so nothing to update"
        }

        val updatedJobDesc = jobDesc.copy(jobStatus = JobStatus.ST_ERROR, log = Some(updatedLog))
        jobModelDAO.putJob(updatedJobDesc)

        ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
        originalSender ! akka.actor.Status.Failure(new RuntimeException(s"No scanTarget is waiting for ${jobDesc.jobId} so nothing to update"))
      case Right(Some(scanTarget)) =>
        block(scanTarget)
    })

  /**
    * update the proxy location in the database
    * @param proxyUri new proxy location URI
    * @param archiveEntry ArchiveEntry instance of the media that this proxy is for
    * @return a Future with either an error string or a success containing the updated record (if available)
    */
  def updateProxyRef(proxyUri:String, archiveEntry:ArchiveEntry, proxyType:ProxyType.Value) = {
    logger.debug(s"updateProxyRef: got $proxyUri in with archive entry in region ${archiveEntry.region}")
    implicit val s3Client = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"), archiveEntry.region)
    ProxyLocation.fromS3(
      proxyUri = proxyUri,
      mainMediaUri = s"s3://${archiveEntry.bucket}/${archiveEntry.path}",
      proxyType = Some(proxyType)
    )
      .flatMap({
        case Left(err) =>
          logger.error(s"Could not get proxy location: $err")
          Future(Left(err))
        case Right(proxyLocation) =>
          logger.info("Saving proxy location...")
          proxyLocationDAO.saveProxy(proxyLocation).map({
            case None =>
              Right(None)
            case Some(Left(err)) =>
              Left(err.toString)
            case Some(Right(updatedLocation)) =>
              logger.info(s"Updated location: $updatedLocation")
              Right(Some(updatedLocation))
          })
      })
  }

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
        val proxyType = jobDesc.jobType.toLowerCase match {
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
        }).recover({
          case err:Throwable=>
            logger.error("Could not update proxy: ", err)
            originalSender ! akka.actor.Status.Failure(err)
        })
      }

    /**
      * handle response message from a "check setup" or "setup transcoder" request
      */
    case HandleCheckSetup(msg, jobDesc, rq, receiptHandle, originalSender)=>
      withScanTarget(msg, jobDesc, rq, receiptHandle, originalSender) {scanTarget=>
        val actualStatus = msg.status match {
          case JobReportStatus.SUCCESS=>JobStatus.ST_SUCCESS
          case JobReportStatus.FAILURE=>JobStatus.ST_ERROR
          case JobReportStatus.RUNNING=>JobStatus.ST_RUNNING
          case JobReportStatus.WARNING=>JobStatus.ST_RUNNING
        }
        val updatedPendingJobIds = scanTarget.pendingJobIds.map(_.filter(value=>value!=jobDesc.jobId))
        val tcReport = TranscoderCheck(ZonedDateTime.now(),actualStatus,msg.decodedLog.collect({
          case Left(err)=>err
          case Right(msg)=>msg
        }))

        val updatedScanTarget = scanTarget.copy(pendingJobIds = updatedPendingJobIds, transcoderCheck = Some(tcReport))
        scanTargetDAO.put(updatedScanTarget)

        val updatedJobDesc = jobDesc.copy(jobStatus = JobStatus.ST_SUCCESS, completedAt = Some(ZonedDateTime.now()))
        jobModelDAO.putJob(updatedJobDesc)

        if(jobDesc.jobStatus==JobStatus.ST_ERROR){
          ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
        } else {
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          originalSender ! akka.actor.Status.Success()
        }
      }

    case HandleSuccessfulAnalyse(msg, jobDesc, rq, receiptHandle, originalSender)=>
      withArchiveEntry(msg, jobDesc, rq, receiptHandle, originalSender) { entry=>
        val updatedEntry = entry.copy(mediaMetadata = msg.metadata)
        indexer.indexSingleItem(updatedEntry).map({
          case Failure(err)=>
            logger.error("Could not update index: ", err)
            val updatedLog = jobDesc.log match {
              case Some(existingLog) => existingLog + "\n" + s"Could not update index: ${err.toString}"
              case None => s"Could not update index: ${err.toString}"
            }
            val updatedJobDesc = jobDesc.copy(jobStatus = JobStatus.ST_ERROR, log = Some(updatedLog))
            jobModelDAO.putJob(updatedJobDesc)
            ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
            originalSender ! akka.actor.Status.Failure(err)
          case Success(newId)=>
            logger.info(s"Updated media metadata for $newId")
            sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
            originalSender ! akka.actor.Status.Success()
        })
      }

    case HandleGenericSuccess(msg, jobDesc, rq, receiptHandle, originalSender)=>
      val updatedJob = jobDesc.copy(jobStatus = JobStatus.ST_SUCCESS, completedAt = Some(ZonedDateTime.now()), log=msg.decodedLog.collect({
        case Left(err)=>err
        case Right(log)=>log
      }))
      jobModelDAO.putJob(jobDesc).map({
        case None=>
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          originalSender ! akka.actor.Status.Success()
        case Some(Right(mdl))=>
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          originalSender ! akka.actor.Status.Success()
        case Some(Left(err))=>
          logger.error(s"Could not update job description: $err")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(err.toString))
      })

    /**
      * route a success message to the appropriate handler
      */
    case HandleSuccess(msg, jobDesc, rq, receiptHandle, originalSender)=>
      logger.debug(s"Got success for jobDesc $jobDesc")
      jobDesc.jobType match {
        case "proxy"=>self ! HandleSuccessfulProxy(msg, jobDesc, rq, receiptHandle, originalSender)
        case "thumbnail"=>self ! HandleSuccessfulProxy(msg, jobDesc, rq, receiptHandle, originalSender)
        case "analyse"=>self ! HandleSuccessfulAnalyse(msg, jobDesc, rq, receiptHandle, originalSender)
        case _=>
          logger.error(s"Don't know how to handle job type ${jobDesc.jobType} coming back from transcoder")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Don't know how to handle job type ${jobDesc.jobType} coming back from transcoder"))
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
          if(msg.jobId == "test-job"){
            //delete out "test-job" which are used for manual testing
            sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          }
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not process $msg: No job found for ${msg.jobId}"))
        case Some(Left(err)) =>
          logger.error(s"Could not look up job for $msg: ${err.toString}")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not look up job for $msg: ${err.toString}"))
        case Some(Right(jobDesc)) =>
          logger.debug(s"Got jobDesc $jobDesc")
          logger.debug(s"jobType: ${jobDesc.jobType} jobReportStatus: ${msg.status}")
          if(jobDesc.jobType=="CheckSetup" || jobDesc.jobType=="SetupTranscoding"){
            ownRef ! HandleCheckSetup(msg,jobDesc, rq, receiptHandle, originalSender)
          } else {
            msg.status match {
              case JobReportStatus.SUCCESS => ownRef ! HandleSuccess(msg, jobDesc, rq, receiptHandle, originalSender)
              case JobReportStatus.FAILURE => ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
              case JobReportStatus.RUNNING => ownRef ! HandleRunning(msg, jobDesc, rq, receiptHandle, originalSender)
            }
          }
      }).recover({
        case err:Throwable=>
          logger.error(s"Could not look up job for $msg in database", err)
      })
    case other:GenericSqsActor.SQSMsg => handleGeneric(other)
  }
}
