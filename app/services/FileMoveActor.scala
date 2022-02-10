package services

import java.time.ZonedDateTime
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.{Indexer, ProxyLocationDAO}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobModelDAO, JobStatus, ScanTarget, SourceType}
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import services.FileMove.GenericMoveActor.MoveActorMessage
import services.FileMove.{CopyMainFile, CopyProxyFiles, DeleteOriginalFiles, GenericMoveActor, UpdateIndexRecords, VerifySource}
import akka.pattern.ask
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

//step one: verify file exists [VerifySource[

//step two: verify dest collection exists [Inline]

//step three: gather proxies [VerifySource]

//step four: copy file to new location [CopyMainFile]

//step five: copy proxies to new location [CopyProxyFile]

//step six: if all copies succeed, update index records [UpdateIndexRecords]

//step seven: remove original files

object FileMoveActor {

  case class MoveFile(sourceFileId:String, destination:ScanTarget, async:Boolean) extends MoveActorMessage

  //replies
  case object MoveSuccess extends MoveActorMessage
  case class MoveAsync(jobId:String) extends MoveActorMessage
  case class MoveFailed(reason:String) extends MoveActorMessage
}


/**
  * this actor uses the same technique as Project Locker to run a step-function and roll back all successful stages if a
  * stage fails
  */
@Singleton
class FileMoveActor @Inject() (config:Configuration,
                               proxyLocationDAO: ProxyLocationDAO,
                               esClientManager:ESClientManager,
                               dynamoClientManager: DynamoClientManager,
                               jobModelDAO: JobModelDAO,
                               s3ClientManager: S3ClientManager)(implicit system:ActorSystem, mat:Materializer)
  extends Actor {
  import FileMoveActor._
  import GenericMoveActor._
  import services.FileMove.GenericMoveActor._

  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val esClient = esClientManager.getClient()
  private implicit val dynamoClient = dynamoClientManager.getNewAsyncDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")
  private val indexer = new Indexer(indexName)

  private implicit val timeout:akka.util.Timeout = 600 seconds  //time out after 10 minutes

  protected val fileMoveChain:Seq[ActorRef] = Seq(
    system.actorOf(Props(new VerifySource(indexer, proxyLocationDAO))),
    system.actorOf(Props(new CopyMainFile(s3ClientManager, config))),
    system.actorOf(Props(new CopyProxyFiles(s3ClientManager, config))),
    system.actorOf(Props(new UpdateIndexRecords(indexer, proxyLocationDAO))),
    system.actorOf(Props(new DeleteOriginalFiles(s3ClientManager, indexer, config)))
  )

  def runNextActorInChain(initialData:FileMoveTransientData, otherSteps:Seq[ActorRef]):Future[Either[StepFailed,StepSucceeded]] = {
    logger.debug(s"runNextActorInChain: remaining chain is $otherSteps")
    if(otherSteps.isEmpty) return Future(Right(StepSucceeded(initialData)))

    val nextActor = otherSteps.head
    logger.debug(s"Sending PerformStep to $nextActor")
    (nextActor ? GenericMoveActor.PerformStep(initialData) ).mapTo[MoveActorMessage].flatMap({
      case successMsg:StepSucceeded=>
        logger.debug(s"Step succeeded, moving to next")
        runNextActorInChain(successMsg.updatedData,otherSteps.tail) flatMap {
          case Left(failedMessage)=>  //if the _next_ step fails, tell _this_ step to roll back
            (nextActor ? GenericMoveActor.RollbackStep(successMsg.updatedData)).map(_=>Left(failedMessage)) //overwrite return value with the original failure
          case Right(nextActorSuccess)=>
            Future(Right(nextActorSuccess))
        }
      case failedMessage:StepFailed=>  //if the step fails, tell it to roll back
        logger.error(s"StepFailed, sending rollback to $nextActor")
        (nextActor ? RollbackStep(initialData)).map(_=>Left(failedMessage))
      case other:Any =>
        logger.warn(s"got unexpected message: ${other.getClass}")
        Future(Left(StepFailed(initialData,"got unexpected message")))
    })
  }

  override def receive:Receive = {
    case MoveFile(sourceFileId, destination, isAsync)=>
      val originalSender = sender()
      val newJob = JobModel.newJob("FileMove",sourceFileId,SourceType.SRC_MEDIA).copy(jobStatus = JobStatus.ST_RUNNING)
      jobModelDAO
        .putJob(newJob)
        .map(_=>{
          val setupData = FileMoveTransientData.initialise(sourceFileId, destination.bucketName, destination.proxyBucket, destination.region)

          logger.info(s"Setting up file move with initial data $setupData")
          runNextActorInChain(setupData, fileMoveChain).map({
            case Right(_) =>
              logger.info(s"File move for $sourceFileId -> ${destination.bucketName} completed successfully")
              val finalJob = newJob.copy(jobStatus = JobStatus.ST_SUCCESS, completedAt = Some(ZonedDateTime.now()))
              jobModelDAO.putJob(finalJob)
              originalSender ! MoveSuccess
            case Left(errMsg) =>
              logger.error(s"File move for $sourceFileId -> ${destination.bucketName} failed: ${errMsg.err}")
              val finalJob = newJob.copy(jobStatus = JobStatus.ST_ERROR, log=Some(errMsg.err), completedAt = Some(ZonedDateTime.now()))
              jobModelDAO.putJob(finalJob)
              originalSender ! MoveFailed(errMsg.err)
          }).recover({
            case err: Throwable =>
              val finalJob = newJob.copy(jobStatus = JobStatus.ST_ERROR, log=Some(err.toString), completedAt = Some(ZonedDateTime.now()))
              jobModelDAO.putJob(finalJob)
              logger.error(s"File move processor crashed: ", err)
              originalSender ! akka.actor.Status.Failure(err)
          })
          if(isAsync){
            originalSender ! MoveAsync(newJob.jobId)
          }
      }).recover({
        case err:Throwable=>
          logger.error(s"Could not move file with id $sourceFileId to destination $destination: ${err.getMessage}", err)
          originalSender ! MoveFailed(err.toString)
      })
  }
}
