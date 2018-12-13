package services

import java.time.ZonedDateTime
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.stream.alpakka.s3.auth.AWSSessionCredentials
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.elastictranscoder.model._
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import com.theguardian.multimedia.archivehunter.common.clientManagers._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import com.theguardian.multimedia.archivehunter.common.errors.NothingFoundError
import com.theguardian.multimedia.archivehunter.common.cmn_services.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common._
import javax.inject.Inject
import models.{AwsSqsMsg, TranscoderState}
import org.apache.logging.log4j.LogManager
import play.api.Logger

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ETSProxyActor {
  trait ETSMsg
  trait ETSMsgReply extends ETSMsg
  /**
    * public message to start proxy creation process
    * @param entry archive entry to proxy
    */
  case class CreateMediaProxy(entry:ArchiveEntry, proxyType: ProxyType.Value) extends ETSMsg

  /**
    * public message reply if something went wrong
    * @param err
    */
  case class PreparationFailure(err:Throwable) extends  ETSMsgReply

  /**
    * public message reply if it worked
    */
  case class PreparationSuccess(transcodeId:String, jobId:String) extends ETSMsgReply

  /* --- Private messages --- */
  /**
    * private message dispatched when a pipeline is ready
    * @param entry
    * @param pipeline
    */
  case class GotTranscodePipeline(entry:ArchiveEntry, targetProxyBucket:String, jobDesc:JobModel, pipelineId:String, proxyType: ProxyType.Value, originalSender: ActorRef) extends ETSMsg

  /**
    * private message dispatched when we need to find a pipeline
    * @param entry
    */
  case class GetTranscodePipeline(entry:ArchiveEntry, targetProxyBucket:String, jobDesc:JobModel, proxyType: ProxyType.Value, originalSender:ActorRef) extends ETSMsg

  /**
    * private messages dispatched when we get status updates from ETS
    */
  case class TranscodeFailed(jobDesc:JobModel, errorCode: Option[Int], message:String) extends ETSMsg
  case class TranscodeProgress(jobDesc:JobModel) extends ETSMsg
  case class TranscodeWarning(jobDesc:JobModel, errorCode:Option[Int], message:String) extends ETSMsg
  case class TranscodeSuccess(jobDesc:JobModel, transcodeUrl:String) extends ETSMsg

  /* --- Internal timer messages --- */
  /**
    * private message dispatched from a timer to check waiting pipelines
    */
  case object CheckPipelinesStatus extends ETSMsg

  /**
    * private message dispatched from a timer to check for any waiting messages on the queue
    */
  case object CheckForNotifications extends ETSMsg

  /* --- State objects ---*/
  /**
    * state object that describes something we are waiting for
    * @param entry
    * @param jobDesc
    * @param pipelineId
    */
  case class WaitingOperation(entry:ArchiveEntry, jobDesc:JobModel, pipelineId:String, targetProxyBucket:String,proxyType:ProxyType.Value, originalSender:ActorRef)
}

/**
  * handle proxying via Elastic Transcoder.  The program flow is as follows:
  *
  * (client) -> CreateMediaProxy -> GetTranscodePipeline     ------------------------------------> GotTranscodePipeline -> [start transcodeJob, save JobDesc]
  *                                          |                                                             |
  *                                          \--> [create pipeline, save record for timed poll]            |
  *                                                                   |                                    |
  * (timer singleton) -> CheckPipelinesStatus -------------------------------------------------------------/
  *
  * (timer singleton) -> CheckForNotifications -> [pull messages from SQS] -> Transcode{Failed,Progress,Warning,Success} -> [update records]
  *
  * @param config implicitly provided [[ArchiveHunterConfiguration]]
  * @param sqsClientMgr implicitly provided [[SQSClientManager]]
  * @param etsClientMgr implicitly provided [[ETSClientManager]]
  * @param s3ClientMgr implicitly provided [[S3ClientManager]]
  * @param esClientMgr implicitly provided [[ESClientManager]]
  * @param scanTargetDAO implicitly provided [[ScanTargetDAO]]
  * @param jobModelDAO implicitly provided [[JobModelDAO]]
  * @param ddbClientMgr implicitly provided [[DynamoClientManager]]
  * @param actorSystem implicitly provided ActorSystem
  */
class ETSProxyActor @Inject() (implicit config:ArchiveHunterConfiguration,
                               sqsClientMgr:SQSClientManager, etsClientMgr: ETSClientManager, s3ClientMgr:S3ClientManager, esClientMgr:ESClientManager,
                               scanTargetDAO: ScanTargetDAO, jobModelDAO: JobModelDAO,
                            ddbClientMgr:DynamoClientManager, actorSystem: ActorSystem) extends Actor{
  import ETSProxyActor._

  implicit val ec:ExecutionContext = actorSystem.dispatcher
  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val etsClient = etsClientMgr.getClient(awsProfile)
  private implicit val ddbClient= ddbClientMgr.getClient(awsProfile)
  private implicit val alpakkaDDBClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)
  private implicit val logger = LogManager.getLogger(getClass)
  private implicit val esClient = esClientMgr.getClient()
  private implicit val s3Client = s3ClientMgr.getClient(awsProfile)

  private val sqsClient = sqsClientMgr.getClient(awsProfile)
  private val indexer = new Indexer(config.get[String]("externalData.indexName"))
  var pipelinesToCheck:Seq[WaitingOperation] = Seq()

  implicit val proxyLocationDAO = new ProxyLocationDAO(config.get[String]("proxies.tableName"))

  /**
    * recursively check the pipelinesToCheck list, removing from the list any that have become ready
    * @param moreToCheck
    * @param notReady
    * @return
    */
  protected def checkNextPipeline(moreToCheck:Seq[WaitingOperation], notReady:Seq[WaitingOperation]=Seq()):Seq[WaitingOperation] = {
    if(moreToCheck.isEmpty) return notReady
    val checking = moreToCheck.head
    val pipelineId = checking.pipelineId
    ProxyGenerators.getPipelineStatus(pipelineId) match {
      case Success(status)=>
        logger.info(s"Status for $pipelineId is $status")
        if(status.toLowerCase()=="active") {
          logger.info(s"Status is ACTIVE, informing actor")
          self ! GotTranscodePipeline(checking.entry, checking.targetProxyBucket, checking.jobDesc, pipelineId, checking.proxyType, checking.originalSender)
          checkNextPipeline(moreToCheck.tail,notReady)
        } else {
          logger.info("Status is not ACTIVE, continuing")
          checkNextPipeline(moreToCheck.tail,notReady ++ Seq(moreToCheck.head))
        }
    }
  }

  /**
    * Generate a random alphanumeric string
    * @param length number of characters in the string
    * @return the random string
    */
  protected def randomAlphaNumericString(length: Int): String = {
    val chars = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
    randomStringFromCharList(length, chars)
  }

  protected def randomStringFromCharList(length: Int, chars: Seq[Char]): String = {
    val sb = new StringBuilder
    for (i <- 1 to length) {
      val randomNum = util.Random.nextInt(chars.length)
      sb.append(chars(randomNum))
    }
    sb.toString
  }

  /**
    * called repeatedly to pull messages from the SQS queue that receives transcoder messages.
    *
    * @param rq ReceiveMessageRequest describing what to pull
    * @param notificationsQueue SQS queue URL that we are pulling from
    * @return a boolean indicating whether anything was received or not.  The method should be called repeatedly until
    *         this returns false, at which point the queue will be emptied.
    */
  def handleNextSqsMessage(rq:ReceiveMessageRequest, notificationsQueue:String):Boolean = {
    val result = sqsClient.receiveMessage(rq)
    val msgList = result.getMessages.asScala
    if(msgList.isEmpty) return false

    msgList.foreach(msg=> {
      logger.debug(s"Received message ${msg.getMessageId}:")
      logger.debug(s"\tAttributes: ${msg.getAttributes.asScala}")
      logger.debug(s"\tReceipt Handle: ${msg.getReceiptHandle}")
      logger.debug(s"\tBody: ${msg.getBody}")

      val etsMsg = AwsSqsMsg.fromJsonString(msg.getBody).flatMap(_.getETSMessage)
      val maybeMd = etsMsg.map(_.userMetadata)

      maybeMd match {
        case Left(err) =>
          logger.error(s"Could not parse message: ${err.toString}")
        case Right(Some(md)) =>
          md.get("archivehunter-job-id") match {
            case Some(jobId) =>
              jobModelDAO.jobForId(jobId).map({
                case None =>
                  logger.error(s"Received message for non-existent job ID $jobId")
                case Some(Left(err)) =>
                  logger.error(s"Could not retrieve information for job ID $jobId: ${err.toString}")
                case Some(Right(jobDesc)) =>
                  val etsMessage = etsMsg.right.get
                  etsMessage.state match {
                    case TranscoderState.ERROR =>
                      self ! TranscodeFailed(jobDesc, etsMessage.errorCode, etsMessage.messageDetails.getOrElse("not provided"))
                    case TranscoderState.PROGRESSING =>
                      self ! TranscodeProgress(jobDesc)
                    case TranscoderState.COMPLETED =>
                      val transcodePath = etsMessage.outputs.flatMap(_.headOption.map(_.key))
                      logger.debug(s"transcodePath is $transcodePath")
                      val transcodeUrl = jobDesc.transcodeInfo.flatMap(tcInfo =>
                        transcodePath.map(tcPath => s"s3://${tcInfo.destinationBucket}/$tcPath"))
                      transcodeUrl match {
                        case None =>
                          logger.error("Job completed but with no transcode URL? this must be a bug")
                        case Some(actualTranscodeUrl) =>
                          self ! TranscodeSuccess(jobDesc, actualTranscodeUrl)
                      }
                    case TranscoderState.WARNING =>
                      self ! TranscodeWarning(jobDesc, etsMessage.errorCode, etsMessage.messageDetails.getOrElse("not provided"))
                  }
              })
              sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(notificationsQueue).withReceiptHandle(msg.getReceiptHandle))
            case None =>
              logger.error("Job message had user metadata but no archivehunter-job-id, can't process it.")
              sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(notificationsQueue).withReceiptHandle(msg.getReceiptHandle))
          }
        case Right(None) =>
          logger.error("Can't process message with no user metadata! Need the internal Job ID")
      }
    })
    true
  }


  override def receive:Receive = {
    /**
      * timed message, check on the operations we have waiting and trigger any that are ready.
      */
    case CheckPipelinesStatus=>
      if(pipelinesToCheck.nonEmpty){
        logger.info(s"Checking status on ${pipelinesToCheck.length} creating pipelines...")
        pipelinesToCheck = checkNextPipeline(pipelinesToCheck)
        logger.info(s"Check complete, now ${pipelinesToCheck.length} pipelines waiting")
      }

    /** timed message, check for messages in the message queue and process them.
      *
      */
    case CheckForNotifications=>
      logger.debug("CheckForNotifications")
      val notificationsQueue=config.get[String]("proxies.notificationsQueue")
      val rq = new ReceiveMessageRequest().withQueueUrl(notificationsQueue)
      var moreMessages=true
      if(notificationsQueue=="queueUrl"){
        logger.warn("notifications queue not set up in applications.conf")
      } else {
        do {
          moreMessages = handleNextSqsMessage(rq, notificationsQueue)
        } while (moreMessages)
      }
    /**
      * private message, sent when we get an error notification from the queue
      */
    case TranscodeFailed(jobDesc, errorCode, message)=>
      logger.error(s"Transcode for job ${jobDesc.jobId} (${jobDesc.transcodeInfo.map(_.transcodeId)}) failed with error $message, logging")
      val updatedJob = jobDesc.copy(jobStatus = JobStatus.ST_ERROR,log = Some(message),completedAt = Some(ZonedDateTime.now()))
      jobModelDAO.putJob(updatedJob)

    case TranscodeProgress(jobDesc)=>
      logger.info(s"Transcode for job ${jobDesc.jobId} (${jobDesc.transcodeInfo.map(_.transcodeId)}) is progressing")
      //if the transcode completed or errored very quickly, this message might arrive after the completion one; don't over-write it if so.
      if(jobDesc.jobStatus!=JobStatus.ST_RUNNING && jobDesc.jobStatus!=JobStatus.ST_SUCCESS && jobDesc.jobStatus!=JobStatus.ST_ERROR){
        val updatedJob = jobDesc.copy(jobStatus = JobStatus.ST_RUNNING)
        jobModelDAO.putJob(updatedJob)
      }

    case TranscodeWarning(jobDesc, maybeErrorCode, message)=>
      logger.warn(s"Transcode for job ${jobDesc.jobId} (${jobDesc.transcodeInfo.map(_.transcodeId)}) gave a warning: $message")
      val updatedLog = jobDesc.log match {
        case None=>message
        case Some(existingMessage)=>existingMessage + "\n" + message
      }
      val updatedJob=jobDesc.copy(log=Some(updatedLog))
      jobModelDAO.putJob(updatedJob)

    case TranscodeSuccess(jobDesc, transcodeUrl)=>
      jobDesc.transcodeInfo match {
        case None =>
          logger.error("job indicated as reported success has no transcode info! Can't register the success.")
        case Some(transcodeInfo: TranscodeInfo) =>
          logger.info(s"Transcode for job ${jobDesc.jobId} (${transcodeInfo.transcodeId}) reported success")
          val updatedJob = jobDesc.copy(jobStatus = JobStatus.ST_SUCCESS, completedAt = Some(ZonedDateTime.now()))
          val jobModelUpdateFuture = jobModelDAO.putJob(updatedJob)
          val indexUpdateFuture = indexer.getById(jobDesc.sourceId).flatMap(entry=>indexer.indexSingleItem(entry.copy(proxied = true)))
          val proxyLocationUpdateFuture = ProxyLocation.newInS3(transcodeUrl,jobDesc.sourceId,transcodeInfo.proxyType) match {
            case Failure(err)=>
              logger.error(s"Could not update proxy ref for $transcodeUrl (${transcodeInfo.proxyType}): ", err)
              Future.failed(err)
            case Success(proxyLocation)=>
              proxyLocationDAO.saveProxy(proxyLocation)
          }

          Future.sequence(Seq(jobModelUpdateFuture, indexUpdateFuture, proxyLocationUpdateFuture)).onComplete({
            case Success(results)=>
              logger.info("Completed updating proxy data")
            case Failure(err)=>
              logger.error("Unable to update proxy records after completed transcode", err)
          })
      }

    /** private message, find an appropriate pipeline for the parameters and trigger immediately if so;
      * otherwise start the pipeline creation process and check it regularly
      */
    case GetTranscodePipeline(entry:ArchiveEntry, targetProxyBucket:String, jobDesc:JobModel, proxyType, originalSender)=>
      logger.info(s"Looking for pipeline for $entry")
      ProxyGenerators.findPipelineFor(entry.bucket, targetProxyBucket) match {
        case Failure(err)=>
          logger.error(s"Could not look up pipelines for $entry", err)
          originalSender ! PreparationFailure(err)
        case Success(pipelines)=>
          if(pipelines.isEmpty){  //nothing present, so we must create a pipeline.
            val newPipelineName = s"archivehunter_${randomAlphaNumericString(10)}"
            ProxyGenerators.createEtsPipeline(newPipelineName, entry.bucket, targetProxyBucket) match {
              case Success(pipeline)=>
                logger.info(s"Initiated creation of $newPipelineName, starting status check")
                pipelinesToCheck = pipelinesToCheck ++ Seq(WaitingOperation(entry, jobDesc, pipeline.getId, targetProxyBucket, proxyType, originalSender))
              case Failure(err)=>
                logger.error(s"Could not create new pipeline for $newPipelineName", err)
                originalSender ! PreparationFailure(err)
            }
          } else {
            logger.info(s"Found ${pipelines.length} potential pipelines for ${entry.bucket} -> $targetProxyBucket, using the first")
            self ! GotTranscodePipeline(entry, targetProxyBucket, jobDesc, pipelines.head.getId, proxyType, originalSender)
          }
      }

    case GotTranscodePipeline(entry:ArchiveEntry, targetProxyBucket:String, jobDesc:JobModel, pipelineId: String, proxyType, originalSender)=>
      logger.info(s"Got transcode pipeline $pipelineId for $jobDesc")
      try {
        val presetId = proxyType match {
          case ProxyType.VIDEO => config.get[String]("proxies.videoPresetId")
          case ProxyType.AUDIO => config.get[String]("proxies.audioPresetId")
          case _ => throw new RuntimeException(s"Request for incompatible proxy type $proxyType")
        }

        ProxyGenerators.outputFilenameFor(presetId, entry.path) match {
          case Failure(err) =>
            logger.error(s"Could not look up preset ID $presetId", err)
          case Success(outputPath) =>
            val rq = new CreateJobRequest()
              .withInput(new JobInput().withKey(entry.path))
              .withOutput(new CreateJobOutput().withKey(outputPath).withPresetId(presetId))
              .withPipelineId(pipelineId)
              //base64 encoded version of this can be no more than 256 bytes!
              .withUserMetadata(Map("archivehunter-job-id" -> jobDesc.jobId).asJava)

            try {
              val result = etsClient.createJob(rq)
              val destinationUrl = s"s3://${}"
              val updatedJobDesc = jobDesc.copy(transcodeInfo = Some(TranscodeInfo(transcodeId = result.getJob.getId, destinationBucket = targetProxyBucket, proxyType = proxyType)))
              jobModelDAO.putJob(updatedJobDesc).map(putJobResult=>{
                originalSender ! PreparationSuccess(result.getJob.getId, jobDesc.jobId)
              })
              //we stop tracking the job here; rely on data coming back via the message queue to inform us what is happening
              logger.info(s"Started transcode for ${entry.path} with ID ${result.getJob.getId}")

            } catch {
              case ex: Throwable =>
                logger.error("Could not create proxy job: ", ex)
                originalSender ! PreparationFailure(ex)
            }
        }
      } catch {
        case ex:Throwable=>
          originalSender ! PreparationFailure(ex)
      }

    case CreateMediaProxy(entry, proxyType)=>
      val callbackUrl=config.get[String]("proxies.appServerUrl")
      logger.info(s"callbackUrl is $callbackUrl")
      val jobUuid = UUID.randomUUID()

      val originalSender = sender()
      scanTargetDAO.targetForBucket(entry.bucket).map({
        case Some(Right(target)) =>
          val targetProxyBucket = target.proxyBucket
          val preparationFuture = ProxyGenerators.getUriToProxy(entry).flatMap(maybeUriToProxy => {
            logger.info(s"Target proxy bucket is $targetProxyBucket")
            logger.info(s"Source media is $maybeUriToProxy")
            maybeUriToProxy match {
              case None =>
                logger.error("Nothing found to proxy")
                Future(Failure(NothingFoundError("media", "Nothing found to proxy")))
              case Some(uriToProxy) =>
                val jobDesc = JobModel(UUID.randomUUID().toString, "proxy", Some(ZonedDateTime.now()), None, JobStatus.ST_PENDING, None, entry.id, None, SourceType.SRC_MEDIA)
                jobModelDAO.putJob(jobDesc).map({
                  case Some(Left(dynamoError)) =>
                    logger.error(s"Could not save new job description: $dynamoError")
                    Failure(new RuntimeException(dynamoError.toString))
                  case _ => //either None or Some(Right(thing)) indicate success
                    Success(jobDesc)
                })
            }
          })

          preparationFuture.map({
            case Success(jobDesc) =>
              self ! ETSProxyActor.GetTranscodePipeline(entry, targetProxyBucket, jobDesc, proxyType, originalSender)
            case Failure(err) =>
              logger.error("Could not prepare for transcode: ", err)
              originalSender ! PreparationFailure(err)
          })
        case None => throw new RuntimeException(s"Entry's source bucket ${entry.bucket} is not registered")
        case Some(Left(err)) => throw new RuntimeException(err.toString)
      })
  }
}
