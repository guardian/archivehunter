package services

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.stream.alpakka.s3.auth.AWSSessionCredentials
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.elastictranscoder.AmazonElasticTranscoder
import com.amazonaws.services.elastictranscoder.model._
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.clientManagers._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import com.theguardian.multimedia.archivehunter.common.errors.NothingFoundError
import com.theguardian.multimedia.archivehunter.common._
import javax.inject.Inject
import models.{AwsSqsMsg, TranscoderState}
import org.apache.logging.log4j.LogManager
import play.api.Logger

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ETSProxyActor {
  trait ETSMsg
  trait ETSMsgReply extends ETSMsg
  /**
    * public message to start proxy creation process
    * @param entry archive entry to proxy
    * @param proxyType type of proxy to generate - from the [[ProxyType]] enumeration
    */
  case class CreateMediaProxy(entry:ArchiveEntry, proxyType: ProxyType.Value) extends ETSMsg

  /**
    * use the default type for the given MIME type to call CreateMediaProxy
    * @param entry
    */
  case class CreateDefaultMediaProxy(entry:ArchiveEntry) extends ETSMsg

  /**
    * public message to call ETS and re-check the status
    * @param entry
    */
  case class ManualJobStatusRefresh(job:JobModel) extends ETSMsg

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
                               scanTargetDAO: ScanTargetDAO, jobModelDAO: JobModelDAO, proxyLocationDAO:ProxyLocationDAO,
                              proxyGenerators:ProxyGenerators,
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


  /**
    * checks existing pipelines in the account to try to find one that goes from the selected input to the selected
    * output bucket
    * @param inputBucket name of the required source bucket
    * @param outputBucket name of the required destination bucket
    * @return a Sequence containing zero or more pipelines. If no pipelines are found, the sequence is empty.
    */
  protected def findPipelineFor(inputBucket:String, outputBucket:String) = {
    def getNextPage(matches:Seq[Pipeline], pageToken: Option[String]):Seq[Pipeline] = {
      val rq = new ListPipelinesRequest()
      val updatedRq = pageToken match {
        case None=>rq
        case Some(token)=>rq.withPageToken(token)
      }

      val result = etsClient.listPipelines(updatedRq).getPipelines.asScala
      logger.debug(s"findPipelineFor: checking in $result")
      if(result.isEmpty){
        logger.debug(s"findPipelineFor: returning $matches")
        matches
      } else {
        val newMatches = result.filter(p=>p.getOutputBucket==outputBucket && p.getInputBucket==inputBucket)
        logger.debug(s"findPipelineFor: got $newMatches to add")
        matches ++ newMatches
      }
    }

    Try {
      val initialResult = getNextPage(Seq(), None)
      logger.debug(s"findPipelineFor: initial result is $initialResult")
      val finalResult = initialResult.filter(p => p.getName.contains("archivehunter")) //filter out anything that is not ours
      logger.debug(s"findPipelineFor: final result is $finalResult")
      finalResult
    }
  }

  protected def getPipelineStatus(pipelineId:String)(implicit etsClient:AmazonElasticTranscoder) = Try {
    val rq = new ReadPipelineRequest().withId(pipelineId)

    val result = etsClient.readPipeline(rq)
    result.getPipeline.getStatus
  }

  /**
    * kick of the creation of a pipeline. NOTE: the Pipeline object returned will not be usable until it's in an active state.
    * @param pipelineName name of the pipeline to create
    * @param inputBucket input bucket it should point to
    * @param outputBucket output bucket it should point to
    * @return
    */
  protected def createEtsPipeline(pipelineName:String, inputBucket:String, outputBucket:String) = {
    val completionNotificationTopic = config.get[String]("proxies.completionNotification")
    val errorNotificationTopic = config.get[String]("proxies.errorNotification")
    val warningNotificationTopic = config.get[String]("proxies.warningNotification")
    val transcodingRole = config.get[String]("proxies.transcodingRole")

    val createRq = new CreatePipelineRequest()
      .withInputBucket(inputBucket)
      .withName(pipelineName)
      .withNotifications(new Notifications().withCompleted(completionNotificationTopic).withError(errorNotificationTopic).withWarning(warningNotificationTopic).withProgressing(warningNotificationTopic))
      .withOutputBucket(outputBucket)
      .withRole(transcodingRole)

    Try {
      val result = etsClient.createPipeline(createRq)
      val warnings = result.getWarnings.asScala
      if(warnings.nonEmpty){
        logger.warn("Warnings were receieved when creating pipeline:")
        warnings.foreach(warning=>logger.warn(warning.toString))
      }
      result.getPipeline
    }
  }

  private val extensionExtractor = "^(.*)\\.([^\\.]+)$".r

  /**
    * check the provided preset ID to get the container format, and use this to put the correct file extension onto the input path
    * @param presetId preset ID that will be used
    * @param inputPath bucket path to the input media
    * @return the output path, if we could get the preset. Otherwise a Failure with the ETS exception.
    */
  protected def outputFilenameFor(presetId:String,inputPath:String):Try[String] = Try {
    val rq = new ReadPresetRequest().withId(presetId)
    val presetResult = etsClient.readPreset(rq)
    val properExtension = presetResult.getPreset.getContainer
    logger.debug(s"Extension for ${presetResult.getPreset.getDescription} ($presetId) is $properExtension")

    inputPath match {
      case extensionExtractor(barePath:String,xtn:String)=>
        barePath + "." + properExtension
      case _=>
        inputPath + "." + properExtension
    }
  }

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
    getPipelineStatus(pipelineId) match {
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
      findPipelineFor(entry.bucket, targetProxyBucket) match {
        case Failure(err)=>
          logger.error(s"Could not look up pipelines for $entry", err)
          originalSender ! PreparationFailure(err)
        case Success(pipelines)=>
          if(pipelines.isEmpty){  //nothing present, so we must create a pipeline.
            val newPipelineName = s"archivehunter_${randomAlphaNumericString(10)}"
            createEtsPipeline(newPipelineName, entry.bucket, targetProxyBucket) match {
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

        outputFilenameFor(presetId, entry.path) match {
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

    case CreateDefaultMediaProxy(entry)=>
      entry.mimeType.major match {
        case "video"=>self ! CreateMediaProxy(entry, ProxyType.VIDEO)
        case "audio"=>self ! CreateMediaProxy(entry, ProxyType.AUDIO)
          //ETSProxyActor does not handle image files
        case "image"=>sender ! PreparationFailure(new RuntimeException("ETSProxyActor does not handle image files"))
        case "application"=>
          if(entry.mimeType.minor=="octet-stream") {
            self ! CreateMediaProxy(entry, ProxyType.VIDEO) //if it's application/octet-stream, then we don't know; try for video but expect failure.
          } else {
            sender ! PreparationFailure(new RuntimeException(s"ETSProxyActor does not know how to handle ${entry.mimeType.toString}"))
          }
        case _=>sender ! PreparationFailure(new RuntimeException(s"ETSProxyActor does not know how to handle ${entry.mimeType.toString}"))
      }

    case CreateMediaProxy(entry, proxyType)=>
      val callbackUrl=config.get[String]("proxies.appServerUrl")
      logger.info(s"callbackUrl is $callbackUrl")
      val jobUuid = UUID.randomUUID()

      val originalSender = sender()
      scanTargetDAO.targetForBucket(entry.bucket).map({
        case Some(Right(target)) =>
          val targetProxyBucket = target.proxyBucket
          val preparationFuture = proxyGenerators.getUriToProxy(entry).flatMap(maybeUriToProxy => {
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

    case ManualJobStatusRefresh(job)=>
      job.transcodeInfo match {
        case None=>
          val originalSender = sender()
          job.jobType match {
            case "proxy"=>
              val jobLogUpdate = "Proxy job has no transcode info! This should not happen."
              val updatedLog = job.log match {
                case None=>jobLogUpdate
                case Some(existingLog)=>existingLog + "\n" + jobLogUpdate
              }
              val updatedJob = job.copy(jobStatus=JobStatus.ST_ERROR, log=Some(updatedLog))
              jobModelDAO.putJob(updatedJob).onComplete({
                case Success(_)=>
                  originalSender ! PreparationFailure(new RuntimeException("Job has no transcodeinfo"))
                case Failure(err)=>
                  logger.error("Could not update job: ", err)
                  originalSender ! PreparationFailure(new RuntimeException("Job has no transcodeinfo"))
              })
            case _=>
              sender() ! PreparationFailure(new RuntimeException("Job has no transcodeinfo"))
          }

        case Some(transcodeInfo)=>
          val reply = etsClient.readJob(new ReadJobRequest().withId(transcodeInfo.transcodeId))
          val jobInfo = reply.getJob
          val maybeNewStatus = jobStatusFromETSStatus(jobInfo.getStatus)

          val maybeTiming = Option(jobInfo.getTiming)

          val maybeCompletionTime = maybeTiming.flatMap(t=>Option(t.getFinishTimeMillis).map(millis=>ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())))

          val logUpdateLine = maybeNewStatus match {
            case None=>
              s"Invalid job status ${jobInfo.getStatus} was found! Job status details:  ${jobInfo.getOutputs.asScala.map(_.getStatusDetail)}"
            case Some(JobStatus.ST_RUNNING)=>
              "Still running"
            case Some(JobStatus.ST_SUCCESS)=>
              completionLogLine(jobInfo)
            case Some(JobStatus.ST_CANCELLED)=>
              s"Job cancelled in ETS at time ${maybeCompletionTime.map(_.format(DateTimeFormatter.BASIC_ISO_DATE))}"
            case Some(JobStatus.ST_ERROR)=>
              s"Job(s) errored: ${jobInfo.getOutputs.asScala.map(_.getStatusDetail)}"
          }

          val updatedLog = job.log match {
            case Some(logLines)=> logLines + "\n" + logUpdateLine
            case None=> logUpdateLine
          }

          val updatedJob = job.copy(jobStatus = maybeNewStatus.getOrElse(JobStatus.ST_ERROR),
            completedAt=maybeCompletionTime,
            log=Some(updatedLog),
          )
          val originalSender = sender()

          jobModelDAO.putJob(updatedJob).onComplete({
            case Success(Some(Left(err)))=>originalSender ! PreparationFailure(new RuntimeException(s"Could not save to database: $err"))
            case Success(_)=>originalSender ! PreparationSuccess(transcodeInfo.transcodeId, job.jobId)
            case Failure(err)=>
              originalSender ! PreparationFailure(err)
          })
      }
  }

  def completionLogLine(jobInfo:Job) = {
    val submitTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(jobInfo.getTiming.getSubmitTimeMillis),ZoneId.systemDefault())
    val startTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(jobInfo.getTiming.getStartTimeMillis),ZoneId.systemDefault())
    val completionTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(jobInfo.getTiming.getFinishTimeMillis),ZoneId.systemDefault())

    s"Job submitted at ${submitTime.format(DateTimeFormatter.ISO_DATE_TIME)}, started at ${startTime.format(DateTimeFormatter.ISO_DATE_TIME)} and completed at ${completionTime.format(DateTimeFormatter.ISO_DATE_TIME)}"
  }

  def jobStatusFromETSStatus(etsStatus: String) = {
    /**Submitted, Progressing, Complete, Canceled, or Error. */
    if(etsStatus=="Submitted"){
      Some(JobStatus.ST_RUNNING)
    } else if(etsStatus=="Progressing"){
      Some(JobStatus.ST_RUNNING)
    } else if(etsStatus=="Complete"){
      Some(JobStatus.ST_SUCCESS)
    } else if(etsStatus=="Cancelled"){
      Some(JobStatus.ST_CANCELLED)
    } else if(etsStatus=="Error"){
      Some(JobStatus.ST_ERROR)
    } else {
      None
    }
  }
}
