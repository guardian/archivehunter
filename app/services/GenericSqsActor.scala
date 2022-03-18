package services

import java.time.{Instant, ZoneId, ZonedDateTime}

import akka.actor.{Actor, ActorRef, ActorSystem, Status}
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, Message, ReceiveMessageRequest}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, SQSClientManager}
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
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
  case class HandleDomainMessage[T](msg:T, queueUrl:String, receiptHandle:String) extends SQSMsg

  /* this must be sent to indicate that the consumer is ready for the next message */
  case object ReadyForNextMessage extends SQSMsg
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

  protected var msgList:Seq[(Message, String)] = Seq()
  protected var isReady = true

  //override this when implementing, like this: io.circe.parser.parse(body).flatMap(_.as[MessageType])
  def convertMessageBody(body:String):Either[io.circe.Error,MsgType]

  def timestampOfMessage(msg:Message):Option[Long] = {
    msg.getAttributes.asScala.get("SentTimestamp").map(tsString=>{
      logger.debug(s"Timestamp is $tsString")
      tsString.toLong
    })
  }

  def handleGeneric(msg: SQSMsg):Unit = msg match {
    //dispatched to pull all messages off the queue. This "recurses" by dispatching itself if there are messages left on the queue.
    case HandleNextSqsMessage(rq:ReceiveMessageRequest)=>
      val result = sqsClient.receiveMessage(rq)
      msgList = msgList ++ result.getMessages.asScala.map(m=>(m, rq.getQueueUrl))

      if(isReady && msgList.nonEmpty) ownRef ! ReadyForNextMessage
      if(msgList.isEmpty) {
        sender() ! Status.Success
      }

    case ReadyForNextMessage=>  //sent by the subclass to indicate that it is ready for more content
      msgList.headOption match {
        case Some((msg, queueUrl)) =>
          isReady = false
          msgList = msgList.tail

          logger.debug(s"Received message ${msg.getMessageId}:")
          logger.debug(s"\tAttributes: ${msg.getAttributes.asScala}")
          logger.debug(s"\tReceipt Handle: ${msg.getReceiptHandle}")
          logger.debug(s"\tBody: ${msg.getBody}")

          convertMessageBody(msg.getBody) match {
            case Left(err) =>
              logger.error(s"Could not decode message from queue: $err")
              logger.error(s"Message was ${msg.getBody}")
              sender() ! Status.Failure
            case Right(finalMsg) =>
              ownRef ! HandleDomainMessage(finalMsg, queueUrl, msg.getReceiptHandle)
          }
        case None =>
          logger.info(s"No more messages to consume")
          isReady = true
          ownRef ! CheckForNotifications
      }

    case CheckForNotifications=>
      logger.debug("CheckForNotifications")
      val rq = new ReceiveMessageRequest().withQueueUrl(notificationsQueue)
        .withWaitTimeSeconds(10)
        .withMaxNumberOfMessages(10)
      if(notificationsQueue=="queueUrl"){
        logger.warn("notifications queue not set up in applications.conf")
        sender() ! Status.Failure
      } else {
        ownRef ! HandleNextSqsMessage(rq)
      }
  }

}
