package services

import akka.actor.Status.Success
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.Materializer
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, SendMessageRequest}
import com.theguardian.multimedia.archivehunter.common.clientManagers.SQSClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTargetDAO
import io.circe
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import play.api.Configuration
import services.FileMoveActor.MoveFile

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.ExecutionContext
import scala.util.Try

case class FileMoveMessage(fileId:String, toCollection:String)

object FileMoveQueue {
  sealed trait FileMoveMsg
  sealed trait FileMoveResponse

  //responses
  final case class EnqueuedOk(fileId:String) extends FileMoveResponse
  final case class EnqueuedProblem(fileId:String,problem:String) extends FileMoveResponse

  /**
    * used for debugging, in response to GetIdleState. Returns the current idle state of the actor.
    * @param idle if the actor is idle or not
    */
  final case class CurrentIdleState(idle:Boolean) extends FileMoveResponse

  //requests
  /**
    * Sent from a Controller to push a message onto the external SQS queue. Returned EnqueuedOk once message has been enqueued
    * or EnqueuedProblem if it could not be
    * @param fileId file ID to move
    * @param toCollection collection name to move it to
    * @param uid user ID of the requesting user
    */
  final case class EnqueueMove(fileId:String, toCollection:String, uid:String) extends FileMoveMsg

  /**
    * Dispatches "CheckForNotifications" only if the processor is in an idle state, otherwise ignored
    */
  final case object CheckForNotificationsIfIdle extends FileMoveMsg

  /**
    * Updates the "idle" flag to the given state
    */
  final case class InternalSetIdleState(newState:Boolean) extends FileMoveMsg

  /**
    * Request the current value of the 'Idle' flag. Used for testing and debugging.
    * Passes back a message of form CurrentIdleState.
    */
  final case object GetIdleState extends FileMoveMsg
}

/**
  * The FileMoveQueue actor sits atop the FileMoveActor, and passes requests for file-moves out to an SQS queue.
  * It also receives requests in from the queue, based on a timed poll message from ClockSingleton.   It will retrieve messages
  * and request moves for them, provided that there is no move currently in progress.
  * The actor handles its own mutable state via InternalSetIdleState()
  * @param config app configuration
  * @param sqsClientMgr SQSClientManager instance for getting hold of an SQS client
  * @param actorSystem akka actor system
  * @param materializer akka materializer
  * @param scanTargetDAO ScanTargetDAO instance, needed for validating the destination collection name
  * @param fileMoveActor reference to the FileMoveActor
  */
@Singleton
class FileMoveQueue @Inject()(config:Configuration,
                              sqsClientMgr: SQSClientManager,
                              actorSystem:ActorSystem,
                              materializer:Materializer,
                              scanTargetDAO: ScanTargetDAO,
                             @Named("fileMoveActor") fileMoveActor:ActorRef) extends GenericSqsActor[FileMoveMessage]
{
  import GenericSqsActor._
  import FileMoveQueue._

  private val logger = LoggerFactory.getLogger(getClass)
  private var isIdle = true

  override protected val sqsClient: AmazonSQS = sqsClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))
  override protected implicit val implSystem: ActorSystem = actorSystem
  override protected implicit val mat: Materializer = materializer
  override protected val notificationsQueue: String = config.get[String]("filemover.notificationsQueue")
  override protected val ownRef: ActorRef = self
  override protected implicit val ec: ExecutionContext = actorSystem.dispatcher

  override def convertMessageBody(body: String): Either[circe.Error, FileMoveMessage] = {
    io.circe.parser.parse(body).flatMap(_.as[FileMoveMessage])
  }

  override def receive: Receive = {
    //update the internal "idle" flag
    case InternalSetIdleState(newValue)=>
      isIdle = newValue
      sender() ! Success( () )

    //used for testing/debugging to show 'current' idle state. Note that this may be stale by the time it reaches the sender!
    case GetIdleState=>
      sender() ! CurrentIdleState(isIdle)

    //this is called from a timer to poll for more notifications.
    //we only want to check for new notifications if we have no operations currently in progress
    case CheckForNotificationsIfIdle=>
      if(isIdle) {
        ownRef ! CheckForNotifications
      }

    //a user has requested a file move
    case EnqueueMove(fileId, toCollection, uid)=>
      logger.info(s"Received request from $uid to move $fileId to $toCollection")
      val msgBody = FileMoveMessage(fileId, toCollection).asJson.noSpaces
      Try { sqsClient.sendMessage(notificationsQueue, msgBody) } match {
        case scala.util.Success(_)=>
          logger.info(s"Successfully enqueued request for $fileId")
          sender() ! EnqueuedOk(fileId)
        case scala.util.Failure(err)=>
          logger.error(s"Could not enqueue request for $fileId: ${err.getMessage}", err)
          sender() ! EnqueuedProblem(fileId, err.getMessage)
      }

    //a file move process was completed ok
    case FileMoveActor.MoveSuccess(fileId)=>
      logger.info(s"Move of $fileId worked fine")
      ownRef ! InternalSetIdleState(true)   //flag that we are not busy any more

    //a file move process failed
    case FileMoveActor.MoveFailed(fileId, error)=>
      logger.error(s"Could not move file $fileId: $error")
      ownRef ! InternalSetIdleState(true) //flag that we are not busy any more

    //a request for a move came off the queue
    case HandleDomainMessage(msg:FileMoveMessage, rq, receiptHandle)=>
      logger.info(s"Received copy request for ${msg.fileId} to ${msg.toCollection}")

      scanTargetDAO.withScanTarget(msg.toCollection) { scanTarget=>
        if(scanTarget.enabled) {
          fileMoveActor ! MoveFile(msg.fileId, scanTarget, ownRef)
          ownRef ! InternalSetIdleState(false)  //flag that we are busy now
        } else {
          logger.warn(s"Cannot move ${msg.fileId} to ${scanTarget.bucketName} because the scan target is disabled")
        }

        //FIXME - ideally this would be done after the move???
        logger.info(s"Notified actor so removing message from queue")
        try {
          sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))
        } catch {
          case err:Throwable=>
            logger.error(s"Could not delete message for ${msg.fileId} with receipt handle $receiptHandle: ${err.getMessage}", err)
        }
      }

    //other messages are handled by the superclass
    case other:SQSMsg => handleGeneric(other)
  }
}
