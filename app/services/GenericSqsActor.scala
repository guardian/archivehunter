package services

import akka.actor.{Actor, ActorRef, ActorSystem, Status}
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, SQSClientManager}
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._
import io.circe.syntax._
import io.circe.generic.auto._

trait GenericSqsActorMessages {
  trait SQSMsg
}

object GenericSqsActor extends GenericSqsActorMessages {
  case object CheckForNotifications extends SQSMsg

  /* private internal messages */
  case class HandleNextSqsMessage(rq:ReceiveMessageRequest) extends SQSMsg

  /* implement this in an extending actor to be provided with decoded message */
  case class HandleDomainMessage[T](msg:T, rq:ReceiveMessageRequest, receiptHandle:String) extends SQSMsg
}

trait GenericSqsActor[MsgType] extends Actor {
  import GenericSqsActor._
  private val logger = Logger(getClass)

  protected val sqsClient:AmazonSQS
  protected implicit val implSystem:ActorSystem
  protected implicit val mat:Materializer

  protected val notificationsQueue:String

  //override this in testing
  protected val ownRef:ActorRef

  protected implicit val ec:ExecutionContext

  //override this when implementing, like this: io.circe.parser.parse(body).flatMap(_.as[MessageType])
  def convertMessageBody(body:String):Either[io.circe.Error,MsgType]

  def handleGeneric(msg: SQSMsg) = msg match {
    //dispatched to pull all messages off the queue. This "recurses" by dispatching itself if there are messages left on the queue.
    case HandleNextSqsMessage(rq:ReceiveMessageRequest)=>
      val result = sqsClient.receiveMessage(rq)
      val msgList = result.getMessages.asScala
      if(msgList.nonEmpty){
        msgList.foreach(msg=> {
          logger.debug(s"Received message ${msg.getMessageId}:")
          logger.debug(s"\tAttributes: ${msg.getAttributes.asScala}")
          logger.debug(s"\tReceipt Handle: ${msg.getReceiptHandle}")
          logger.debug(s"\tBody: ${msg.getBody}")

           convertMessageBody(msg.getBody) match {
            case Left(err)=>
              logger.error(s"Could not decode message from queue: $err")
              sender ! Status.Failure
            case Right(finalMsg)=>
              ownRef ! HandleDomainMessage(finalMsg, rq, msg.getReceiptHandle)
          }
        })
        ownRef ! HandleNextSqsMessage(rq)
      } else {
        sender ! Status.Success
      }

    case CheckForNotifications=>
      logger.debug("CheckForNotifications")
      val rq = new ReceiveMessageRequest().withQueueUrl(notificationsQueue)
        .withWaitTimeSeconds(10)
        .withMaxNumberOfMessages(10)
      if(notificationsQueue=="queueUrl"){
        logger.warn("notifications queue not set up in applications.conf")
        sender ! Status.Failure
      } else {
        ownRef ! HandleNextSqsMessage(rq)
      }
  }

}
