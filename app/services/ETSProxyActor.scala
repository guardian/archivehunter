package services

import java.time.ZonedDateTime
import java.util.UUID

import akka.actor.{Actor, ActorSystem}
import com.amazonaws.services.elastictranscoder.model.Pipeline
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ETSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import com.theguardian.multimedia.archivehunter.common.errors.NothingFoundError
import com.theguardian.multimedia.archivehunter.common.services.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveHunterConfiguration, ProxyLocationDAO}
import javax.inject.Inject
import org.apache.logging.log4j.LogManager
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ETSProxyActor {
  trait ETSMsg

  /**
    * public message to start proxy creation process
    * @param entry archive entry to proxy
    */
  case class CreateMediaProxy(entry:ArchiveEntry) extends ETSMsg

  /**
    * public message reply if something went wrong
    * @param err
    */
  case class PreparationFailure(err:Throwable) extends  ETSMsg

  /**
    * private message dispatched when a pipeline is ready
    * @param entry
    * @param pipeline
    */
  case class GotTranscodePipeline(entry:ArchiveEntry, jobDesc:JobModel, pipelineId:String) extends ETSMsg

  /**
    * private message dispatched when we need to find a pipeline
    * @param entry
    */
  case class GetTranscodePipeline(entry:ArchiveEntry, targetProxyBucket:String, jobDesc:JobModel) extends ETSMsg
  /**
    * private message dispatched from a timer to check waiting pipelines
    */
  case object CheckPipelinesStatus extends ETSMsg

  case class WaitingOperation(entry:ArchiveEntry, jobDesc:JobModel, pipelineId:String)
}

class ETSProxyActor @Inject() (implicit config:ArchiveHunterConfiguration, etsClientMgr: ETSClientManager, scanTargetDAO: ScanTargetDAO, jobModelDAO: JobModelDAO,
     proxyLocationDAO: ProxyLocationDAO, ddbClientMgr:DynamoClientManager, actorSystem: ActorSystem) extends Actor{
  import ETSProxyActor._

  implicit val ec:ExecutionContext = actorSystem.dispatcher
  val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val etsClient = etsClientMgr.getClient(awsProfile)
  private implicit val ddbClient= ddbClientMgr.getClient(awsProfile)
  private implicit val logger = LogManager.getLogger(getClass)

  var pipelinesToCheck:Seq[WaitingOperation] = Seq()

  protected def checkNextPipeline(moreToCheck:Seq[WaitingOperation], notReady:Seq[WaitingOperation]=Seq()):Seq[WaitingOperation] = {
    if(moreToCheck.isEmpty) return notReady
    val checking = moreToCheck.head
    val pipelineId = checking.pipelineId
    ProxyGenerators.getPipelineStatus(pipelineId) match {
      case Success(status)=>
        logger.info(s"Status for ${moreToCheck.head} is $status")
        if(status=="READY") { //FIXME: check this value
          logger.info(s"Status is READY, informing actor")
          self ! GotTranscodePipeline(checking.entry, checking.jobDesc, pipelineId)
          checkNextPipeline(moreToCheck.tail, notReady)
        } else {
          logger.info("Status is not READY, continuing")
          checkNextPipeline(moreToCheck.tail, notReady ++ Seq(moreToCheck.head))
        }
    }
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

    /** private message, find an appropriate pipeline for the parameters and trigger immediately if so;
      * otherwise start the pipeline creation process and check it regularly
      */
    case GetTranscodePipeline(entry:ArchiveEntry, targetProxyBucket:String, jobDesc:JobModel)=>
      logger.info(s"Looking for pipeline for $entry")
      ProxyGenerators.findPipelineFor(entry.bucket, targetProxyBucket) match {
        case Failure(err)=>
          logger.error(s"Could not look up pipelines for $entry", err)
          sender() ! PreparationFailure(err)
        case Success(pipelines)=>
          if(pipelines.isEmpty){  //nothing present, so we must create a pipeline.
            val newPipelineName = s"archivehunter_${entry.bucket}_$targetProxyBucket"
            ProxyGenerators.createEtsPipeline(newPipelineName, entry.bucket, targetProxyBucket) match {
              case Success(pipeline)=>
                logger.info(s"Initiated creation of $newPipelineName, starting status check")
                pipelinesToCheck = pipelinesToCheck ++ Seq(WaitingOperation(entry, jobDesc, pipeline.getId))
              case Failure(err)=>
                logger.error(s"Could not create new pipeline for $newPipelineName", err)
                sender() ! PreparationFailure(err)
            }
          } else {
            logger.info(s"Found ${pipelines.length} potential pipelines for ${entry.bucket} -> $targetProxyBucket, using the first")
            self ! GotTranscodePipeline(entry, jobDesc, pipelines.head.getId)
          }
      }

    case CreateMediaProxy(entry)=>
      val callbackUrl=config.get[String]("proxies.appServerUrl")
      logger.info(s"callbackUrl is $callbackUrl")
      val jobUuid = UUID.randomUUID()

      val targetProxyBucketFuture = scanTargetDAO.targetForBucket(entry.bucket).map({
        case None=>throw new RuntimeException(s"Entry's source bucket ${entry.bucket} is not registered")
        case Some(Left(err))=>throw new RuntimeException(err.toString)
        case Some(Right(target))=>Some(target.proxyBucket)
      })

      val preparationFuture = Future.sequence(Seq(targetProxyBucketFuture, ProxyGenerators.getUriToProxy(entry))).flatMap(results=> {
        val targetProxyBucket = results.head.get
        val maybeUriToProxy = results(1)
        logger.info(s"Target proxy bucket is $targetProxyBucket")
        logger.info(s"Source media is $maybeUriToProxy")
        maybeUriToProxy match {
          case None=>
            logger.error("Nothing found to proxy")
            Future(Failure(NothingFoundError("media", "Nothing found to proxy")))
          case Some(uriToProxy)=>
            val jobDesc = JobModel(UUID.randomUUID().toString,"proxy",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,entry.id,SourceType.SRC_MEDIA)

            jobModelDAO.putJob(jobDesc).map({
              case Some(Left(dynamoError))=>
                logger.error(s"Could not save new job description: $dynamoError")
                Failure(new RuntimeException(dynamoError.toString))
              case _=>  //either None or Some(Right(thing)) indicate success

                Success(Tuple2(jobDesc, targetProxyBucket))
            })
        }
      })

      val originalSender = sender()
      preparationFuture.map({
        case Success((jobDesc, targetProxyBucket))=>
          self ! ETSProxyActor.GetTranscodePipeline(entry, targetProxyBucket, jobDesc)
        case Failure(err)=>
          logger.error("Could not prepare for transcode: ", err)
          sender ! PreparationFailure(err)
      })
  }
}
