package services

import akka.actor.{Actor, ActorSystem}
import akka.stream.ActorMaterializer
import com.amazonaws.services.simpleemail.model.{AccountSendingPausedException, Destination, MessageRejectedException, SendTemplatedEmailRequest, TemplateDoesNotExistException}
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.clientManagers.{SESClientManager, SQSClientManager}
import io.circe
import javax.inject.{Inject, Singleton}
import models.{EmailTemplateAssociationDAO, SESEncoder, SESEventType, SESMessageFormat, SESRecipientInfo, UserProfile, UserProfileDAO}
import models.EmailableActions.EmailableActions
import play.api.{Configuration, Logger}
import services.SendEmailActor.NotifiableEvent
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.MDC
import services.GenericSqsActor.HandleDomainMessage

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


object SendEmailActor {
  trait SEMsg

  //public messages to send to the actor
  case class NotifiableEvent(eventType:EmailableActions, messageParameters:Map[String,String], intiatingUser:UserProfile, overrideTemplate:Option[String]) extends SEMsg

  //messages sent internally
  case class UpdateUserProfileWithBounce(bounceInfo:SESRecipientInfo)

  //public reply messages to handle
  case object NoEventAssociation extends SEMsg
  case object EventSent extends SEMsg
  case class DBError(errorString: String) extends SEMsg
  case class SESError(errorString:String) extends  SEMsg
  case class GeneralError(errorString:String) extends SEMsg
}


@Singleton
class SendEmailActor @Inject() (config:Configuration, SESClientManager: SESClientManager, sqsClientManager:SQSClientManager, emailTemplateAssociationDAO: EmailTemplateAssociationDAO, userProfileDAO:UserProfileDAO, system:ActorSystem) extends GenericSqsActor[SESMessageFormat] with SESEncoder with ZonedDateTimeEncoder {
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val logger = Logger(getClass)
  protected val sesClient = SESClientManager.getClient(awsProfile)

  import SendEmailActor._

  override protected implicit val ec:ExecutionContext = system.dispatcher
  override protected implicit val implSystem:ActorSystem = system
  override protected implicit val mat:akka.stream.Materializer = ActorMaterializer.create(system)
  override protected val notificationsQueue = config.get[String]("email.sesNotificationsQueue")
  override protected val sqsClient = sqsClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))

  def getRightTemplateName(eventType:EmailableActions, maybeTemplateOverride:Option[String]) = maybeTemplateOverride match {
    case Some(templateOverride)=>Future(Right(templateOverride))
    case None=>
      emailTemplateAssociationDAO.getByAction(eventType).map({
        case None =>
          logger.error(s"Trying to send an email for event $eventType that has no association to an email template")
          Left(NoEventAssociation)
        case Some(Left(err)) =>
          logger.error(s"Could not look up event type->email template mapping in Dynamo: ${err.toString}")
          Left(DBError(err.toString))
        case Some(Right(templateAssociation)) =>
          Right(templateAssociation.templateName)
      })
  }

  override val ownRef = self

  override def convertMessageBody(body: String): Either[circe.Error, SESMessageFormat] =
    io.circe.parser.parse(body).flatMap(_.as[SESMessageFormat])

  override def receive: Receive = {
    case UpdateUserProfileWithBounce(recipientInfo)=>
      userProfileDAO.userProfileForEmail(recipientInfo.emailAddress).map({
        case None=>
          logger.error(s"Bounced email receipt from user ${recipientInfo} that does not appear to have a user profile!")
        case Some(Left(err))=>
          MDC.put("email", recipientInfo.emailAddress)
          MDC.put("action", recipientInfo.action)
          MDC.put("status", recipientInfo.status)
          logger.error(s"Could not update user profile: $err")
        case Some(Right(userProfile))=>
          val updatedUserProfile = userProfile.copy(emailBounceCount = userProfile.emailBounceCount match {
            case None=>Some(1)
            case Some(number)=>Some(number+1)
          })
          userProfileDAO.put(updatedUserProfile)
      })

    case NotifiableEvent(eventType, messageParameters, initiatingUser, maybeOverrideTemplate)=>
      val originalSender = sender()

      getRightTemplateName(eventType, maybeOverrideTemplate).map({
        case Left(errMsg)=>
          originalSender ! errMsg
        case Right(templateName)=>
          val maybeRequest = Try {
            new SendTemplatedEmailRequest()
              .withTemplate(templateName)
              .withConfigurationSetName(config.get[String]("email.configurationSetName"))
              .withTemplateData(messageParameters.asJson.toString())
              .withDestination(new Destination().withToAddresses(initiatingUser.userEmail))
              .withReplyToAddresses(config.get[String]("email.replyToAddress"))
              .withSource(config.get[String]("email.sourceAddress"))
          }

          logger.info(s"Sending email with template $templateName")
          logger.info(s"Sending with parameters ${messageParameters.asJson.toString()}")
          logger.info(s"Sending to ${initiatingUser.userEmail}")
          logger.info(s"Reply to is ${config.get[String]("email.replyToAddress")}")
          logger.info(s"Source email is ${config.get[String]("email.sourceAddress")}")
          logger.info(s"Configuration set name is ${config.get[String]("email.configurationSetName")}")
          logger.info(s"Request is $maybeRequest")

          maybeRequest match {
            case Success(rq) =>
              val result = Try {
                sesClient.sendTemplatedEmail(rq)
              }
              logger.info(s"Send result is $result")
              result match {
                case Success(_) => originalSender ! EventSent
                case Failure(err: MessageRejectedException) =>
                  logger.error(s"Message send was rejected: ", err)
                  //TODO: should probably retry here
                  originalSender ! SESError(err.toString)
                case Failure(err: TemplateDoesNotExistException) =>
                  logger.error(s"Template $templateName did not exist")
                  //TODO: should probably delete association to prevent this happening again
                  originalSender ! SESError(err.toString)
                case Failure(err: AccountSendingPausedException) =>
                  logger.error(s"Email sending is disabled for this AWS account, please investigate")
                  originalSender ! SESError(err.toString)
                case Failure(err: Throwable) =>
                  logger.error(s"Could not send email: ", err)
                  originalSender ! GeneralError(err.toString)
              }
            case Failure(err) =>
              logger.error(s"Could not build SES request: ", err)
              originalSender ! GeneralError(err.toString)
          }
      })

    case HandleDomainMessage(sesMessage:SESMessageFormat, rq, receiptHandle)=>
      sesMessage.eventType match {
        case SESEventType.Bounce=>
          logger.warn("Received email bounce")
          sesMessage.bounce match {
            case Some(bounceInfo)=>
              bounceInfo.bouncedRecipients.foreach(info=>ownRef ! UpdateUserProfileWithBounce(info))
            case None=>
              MDC.put("sesMessage", sesMessage.toString)
              logger.warn(s"Received email bounce notification with no explicit information?")
          }
        case _=>

      }
      sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))

    case other:GenericSqsActor.SQSMsg => handleGeneric(other)
  }
}
