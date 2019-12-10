package services

import java.time.ZonedDateTime

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import helpers.ProxyLocator
import io.circe.generic.auto._
import javax.inject.{Inject, Singleton}
import models.{AwsSqsMsg, JobReportNew, JobReportStatus, JobReportStatusEncoder}
import org.slf4j.MDC
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

  case class UpdateProblemsIndexSuccess(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg

  case class HandleFailure(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleWarning(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
  case class HandleRunning(msg:JobReportNew, jobDesc:JobModel, rq:ReceiveMessageRequest, receiptHandle:String, originalSender:ActorRef) extends PFQMsg
}

trait ProxyFrameworkQueueFunctions extends ProxyTypeEncoder with JobReportStatusEncoder with MediaMetadataEncoder {
  protected val indexer:Indexer
  protected val logger:Logger
  protected implicit val esClient:HttpClient

  def convertMessageBody(body: String) =
    AwsSqsMsg.fromJsonString(body).flatMap(snsMsg=>{
      io.circe.parser.parse(snsMsg.Message).flatMap(_.as[JobReportNew]).map(_.copy(timestamp = Some(ZonedDateTime.parse(snsMsg.Timestamp))))
    })
}
/**
  * Actor that handles messages returned via SQS from the Proxy Framework.  Successful processing ends with the message being
  * deleted from the queue; an error in processing means that the message will NOT be deleted. The hope is that whatever
  * is preventing the message from processing is a transient failure that will succeed at some point in the future.
  * Permanent failures are dealt with by a dead-letter queue in SQS.
  * This is intended to be instansiated via Guice during the app startup in Module,
  * automatically resolving all parameters
  *
  * @param config Configuration object
  * @param system Actor System
  * @param sqsClientManager client manager class instance for SQS
  * @param s3ClientMgr client manager class instance for S3
  * @param dynamoClientMgr client manager class for DynamoDB
  * @param jobModelDAO Data Access Object for job models
  * @param scanTargetDAO Data Access Object for scan targets
  * @param esClientMgr client manager class instance for Elastic Search
  * @param proxyLocationDAO Data Access Object for proxy locations
  */
@Singleton
class ProxyFrameworkQueue @Inject() (config: Configuration,
                                     system: ActorSystem,
                                     sqsClientManager: SQSClientManager,
                                     s3ClientMgr: S3ClientManager,
                                     dynamoClientMgr: DynamoClientManager,
                                     jobModelDAO: JobModelDAO,
                                     scanTargetDAO: ScanTargetDAO,
                                     esClientMgr:ESClientManager
                                    )(implicit proxyLocationDAO: ProxyLocationDAO)
  extends GenericSqsActor[JobReportNew] with ProxyFrameworkQueueFunctions {
  import ProxyFrameworkQueue._
  import GenericSqsActor._

  protected val logger = Logger(getClass)

  override protected val sqsClient = sqsClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))

  override protected implicit val implSystem = system
  override protected implicit val mat: Materializer = ActorMaterializer.create(system)

  //override this in testing
  protected val ownRef: ActorRef = self

  override protected implicit val ec: ExecutionContext = system.dispatcher

  override protected val notificationsQueue = config.get[String]("proxyFramework.notificationsQueue")

  private implicit val ddbClient = dynamoClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  protected implicit val esClient = esClientMgr.getClient()
  protected implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))

  lazy val defaultRegion = config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1")

  protected val problemItemIndexName = config.get[String]("externalData.problemItemsIndex")
  protected val problemItemIndexer = new ProblemItemIndexer(problemItemIndexName)

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
    * looks up a ScanTarget waiting for this job and executes the provided block on it (within a Future) if the lookup succeeds.
    * if the lookup does not succeed then mark the job as failed.
    * @param msg JobReportNew describing the results of the job from the proxy framework
    * @param jobDesc JobModel from the database
    * @param rq ReceiveMessageRequest object from SQS, this is passed straight on to the block
    * @param receiptHandle receipt handle from SQS, to ensure that the message is deleted after successful processing
    * @param originalSender the actor that originally sent us the message. sender() refs get nullified when we go into threads.
    * @param block block to call if ScanTarget can be found.  Must accept the ScanTarget as its only argument, and return unit (i.e. nothing)
    * @return Future of Unit.
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
      * update problem items index to show that an item has been successfully proxied
      */
    case UpdateProblemsIndexSuccess(msg, jobDesc, rq, receiptHandle, originalSender)=>
      logger.info("Updating problems index: ")
      jobDesc.jobType.toLowerCase() match {
        case "proxy" =>
          msg.proxyType match {
            case Some(proxyType) =>
              logger.debug(s"Updating problem item for ${proxyType.toString}")
              problemItemIndexer.getById(jobDesc.sourceId).map(problemItem => {
                val entry = problemItem.copyExcludingResult(proxyType)

                if(entry.verifyResults.nonEmpty) {
                  problemItemIndexer.indexSingleItem(entry)
                } else {
                  problemItemIndexer.deleteEntry(entry)
                }
                ///don't send to originalSender as we are not on the critical path
                //originalSender ! akka.actor.Status.Success

              }).recover({
                case err: Throwable =>
                  logger.warn(s"Could not update problems index for $jobDesc: ", err)
                  //originalSender ! akka.actor.Status.Failure(err)
              })
            case None =>
              logger.warn(s"Can't update problems index for proxy if there is no proxy type")
              //originalSender ! akka.actor.Status.Failure(new RuntimeException("Can't update problems index for proxy if there is no proxy type"))
          }
        case "thumbnail" =>
          logger.debug("Updating problem item for thumbnail")
          problemItemIndexer.getById(jobDesc.sourceId).map(item => {
            val entry = item.copyExcludingResult(ProxyType.THUMBNAIL)
            if(entry.verifyResults.nonEmpty) {
              problemItemIndexer.indexSingleItem(entry)
            } else {
              problemItemIndexer.deleteEntry(entry)
            }
          })
        case _=>
          logger.warn("Can't update problems index for non proxy jobs")
          //originalSender ! akka.actor.Status.Failure(new RuntimeException("Can't update problems index for non proxy jobs"))
      }


    /**
      * if a proxy job completed successfully, then update the proxy location table with the newly generated proxy
      */
    case HandleSuccessfulProxy(msg, jobDesc, rq, receiptHandle, originalSender)=>
      logger.info(s"Outboard process indicated job success: $msg")

      if(msg.output.isEmpty){
        logger.error(s"Message $msg logged as success but had no 'output' field!")
        originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Message logged as success but had no 'output' field!"))
      } else {
        val proxyType = msg.proxyType match {
          case Some(pt)=>pt
          case None =>
            logger.warn("No proxy type information from transcode framework, guessing based on job type")
            jobDesc.jobType.toLowerCase match {
              case "thumbnail" => ProxyType.THUMBNAIL
              case "proxy" => ProxyType.VIDEO
            }
        }

        //set up parallel jobs, one to update dynamo, one to update elasticsearch
        val proxyUpdateFuture = thumbnailJobOriginalMedia(jobDesc).flatMap({
          case Left(err) => Future(Left(err))
          case Right(archiveEntry) => updateProxyRef(msg.output.get, archiveEntry, proxyType)
        })

        val indexUpdateFuture = ProxyLocator.setProxiedWithRetry(jobDesc.sourceId)

        //set up a future to complete when both of the update jobs have run. this marshals a single success/failure flag
        val overallCompletionFuture = Future.sequence(Seq(proxyUpdateFuture, indexUpdateFuture)).map(results=>{
          val errors = results.collect({case Left(err)=>err})
          if(errors.nonEmpty){
            logger.error(s"Could not update proxy: $errors")
            Left(errors.mkString(","))
          } else {
            results.head
          }
        })

        //handle overall success/failure
        overallCompletionFuture.map({
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
            ownRef ! UpdateProblemsIndexSuccess(msg, jobDesc, rq, receiptHandle, originalSender)
            ownRef ! HandleGenericSuccess(msg, jobDesc, rq, receiptHandle, originalSender)
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

        val updatedJobDesc = jobDesc.copy(jobStatus = actualStatus, completedAt = Some(ZonedDateTime.now()))
        jobModelDAO.putJob(updatedJobDesc).onComplete({
          case Success(Some(Left(err)))=>
            logger.error(s"Could not update job ${jobDesc.jobId}: $err")
          case Success(Some(Right(rec)))=>
            logger.info(s"Updated job ${jobDesc.jobId}")
            sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          case Success(None)=>
            logger.info(s"Updated job ${jobDesc.jobId}")
            sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          case Failure(err)=>
            logger.error(s"update thread failed: ", err)
        })

        if(actualStatus==JobStatus.ST_ERROR){
          ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
        } else {
          originalSender ! akka.actor.Status.Success
        }
      }

    /**
      * Handle the response message from a successful "analyse", i.e. determination of system metadata,
      * by pushing the captured metadata into the index record
      */
    case HandleSuccessfulAnalyse(msg, jobDesc, rq, receiptHandle, originalSender)=>
      withArchiveEntry(msg, jobDesc, rq, receiptHandle, originalSender) { entry=>
        logger.info(s"Received updated metadata ${msg.metadata} for ${entry.id}")
        val updatedEntry = entry.copy(mediaMetadata = msg.metadata)
        indexer.indexSingleItem(updatedEntry).map({
          case Left(err)=>
            MDC.put("entry",updatedEntry.toString)
            MDC.put("error", err.toString)
            logger.error(s"Could not update index: $err")
            val updatedLog = jobDesc.log match {
              case Some(existingLog) => existingLog + "\n" + s"Could not update index: ${err.toString}"
              case None => s"Could not update index: ${err.toString}"
            }
            val updatedJobDesc = jobDesc.copy(jobStatus = JobStatus.ST_ERROR, log = Some(updatedLog))
            jobModelDAO.putJob(updatedJobDesc)
            ownRef ! HandleFailure(msg, jobDesc, rq, receiptHandle, originalSender)
            originalSender ! akka.actor.Status.Failure(new RuntimeException(err.toString))
          case Right(newId)=>
            logger.info(s"Updated media metadata for $newId")
            ownRef ! HandleGenericSuccess(msg, jobDesc, rq, receiptHandle, originalSender)
        })
      }

    /**
      * generic stuff for handling success messages - update the job to show completion and logs, then delete the incoming
      * message from SQS
      */
    case HandleGenericSuccess(msg, jobDesc, rq, receiptHandle, originalSender)=>
      logger.info(s"HandleGenericSuccess for $jobDesc")
      val updatedJob = jobDesc.copy(jobStatus = JobStatus.ST_SUCCESS, completedAt = Some(ZonedDateTime.now()), log=msg.decodedLog.collect({
        case Left(err)=>s"$err for ${msg.log}"
        case Right(log)=>log
      }))
      jobModelDAO.putJob(updatedJob).map({
        case None=>
          logger.info(s"Job ${jobDesc.jobId} updated")
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          originalSender ! akka.actor.Status.Success
        case Some(Right(mdl))=>
          logger.info(s"Job ${jobDesc.jobId} updated")
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          originalSender ! akka.actor.Status.Success
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
        case "Analyse"=>self ! HandleSuccessfulAnalyse(msg, jobDesc, rq, receiptHandle, originalSender)
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
          originalSender ! akka.actor.Status.Success
        case Failure(err)=>
          logger.error("Could not update job model in database", err)
          originalSender ! akka.actor.Status.Failure(err)
      })

    case HandleWarning(msg, jobDesc, rq, receiptHandle, originalSender)=>
      val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now), jobStatus = JobStatus.ST_WARNING, log = msg.decodedLog.map(_.fold(
        err=>s"Could not decode job log ${msg.log}: $err",
        logContent=>logContent
      )))

      val proxyType = msg.proxyType match {
        case Some(pt)=>pt
        case None =>
          logger.warn("No proxy type information from transcode framework, guessing based on job type")
          jobDesc.jobType.toLowerCase match {
            case "thumbnail" => ProxyType.THUMBNAIL
            case "proxy" => ProxyType.VIDEO
          }
      }

      val proxyUpdateFuture = msg.output match {
        case Some(output) =>  //if msg.output is set, then we can still set the proxy even though the state is warning.
          thumbnailJobOriginalMedia(jobDesc).flatMap({
            case Left(err) => Future(Left(err))
            case Right(archiveEntry) => updateProxyRef(msg.output.get, archiveEntry, proxyType)
          })
        case None => Future(Right(None))  //if there is no output there's nothing to update, but no failure either.
      }

      proxyUpdateFuture.map({
        case Left(err) =>
          logger.error(s"Could not update proxy: $err")
          val updatedJd = jobDesc.copy(completedAt = Some(ZonedDateTime.now), log = Some(s"Could not update proxy: $err"), jobStatus = JobStatus.ST_ERROR)
          jobModelDAO.putJob(updatedJd).onComplete({
            case Success(_) =>
              logger.error(s"could not update proxy: $err")
              originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not update proxy: $err"))
            case Failure(dbErr) =>
              logger.error(s"job table update failed: ", dbErr)
              originalSender ! akka.actor.Status.Failure(dbErr)
          })
        case Right(_) =>
          jobModelDAO.putJob(updatedJd).onComplete({
            case Success(Some(Left(err)))=>
              logger.error(s"Could not update job model in database: ${err.toString}")
              originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not update job model in database: ${err.toString}"))
            case Success(_)=>
              sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
              originalSender ! akka.actor.Status.Success
            case Failure(err)=>
              logger.error("Could not update job model in database", err)
              originalSender ! akka.actor.Status.Failure(err)
          })
      }).recover({
        case err:Throwable=>
          logger.error("Could not update proxy: ", err)
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
          originalSender ! akka.actor.Status.Success
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

          if(isMessageOutOfDate(jobDesc.lastUpdatedTS, msg.timestamp)){
            logger.info(s"Received outdated message update: job  ${jobDesc.jobId} had ${jobDesc.lastUpdatedTS}, message had ${msg.timestamp}")
            sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
          } else {
            val updatedJobDesc = if(msg.timestamp.isDefined) jobDesc.copy(lastUpdatedTS = msg.timestamp) else jobDesc

            if (updatedJobDesc.jobType == "CheckSetup" || updatedJobDesc.jobType == "SetupTranscoding") {
              ownRef ! HandleCheckSetup(msg, updatedJobDesc, rq, receiptHandle, originalSender)
            } else {
              msg.status match {
                case JobReportStatus.SUCCESS => ownRef ! HandleSuccess(msg, updatedJobDesc, rq, receiptHandle, originalSender)
                case JobReportStatus.FAILURE => ownRef ! HandleFailure(msg, updatedJobDesc, rq, receiptHandle, originalSender)
                case JobReportStatus.RUNNING => ownRef ! HandleRunning(msg, updatedJobDesc, rq, receiptHandle, originalSender)
                case JobReportStatus.WARNING => ownRef ! HandleWarning(msg, updatedJobDesc, rq, receiptHandle, originalSender)
              }
            }
          }
      }).recover({
        case err:Throwable=>
          logger.error(s"Could not look up job for $msg in database: ${err.toString}", err)
      })
    case other:GenericSqsActor.SQSMsg => handleGeneric(other)
  }

  /**
    * check whether the given message is out of date, i.e. the db record has been updated by a message sent after this one.
    * @param maybeJobLast timestamp of the last update message to the job
    * @param maybeMsgTimestamp timestamp of this update message
    * @return Boolean indicating whether the message is out of date. true if it is, and should be discarded; false if it should be procesed.
    */
  private def isMessageOutOfDate(maybeJobLast:Option[ZonedDateTime], maybeMsgTimestamp:Option[ZonedDateTime]) = {
    maybeMsgTimestamp match {
      case None=>
        logger.warn("Got message with no timestamp? can't determine if message is outdated.")
        false
      case Some(msgTimestamp)=>
        maybeJobLast match {
          case None=>
            logger.debug("Job has no timestamp, so we must be first")
            false
          case Some(jobLast)=>
            msgTimestamp.isBefore(jobLast)
        }
    }
  }
}
