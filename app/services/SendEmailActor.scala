package services

import akka.actor.Actor
import com.amazonaws.services.simpleemail.model.{AccountSendingPausedException, Destination, MessageRejectedException, SendTemplatedEmailRequest, TemplateDoesNotExistException}
import com.theguardian.multimedia.archivehunter.common.clientManagers.SESClientManager
import javax.inject.{Inject, Singleton}
import models.{EmailTemplateAssociationDAO, UserProfile}
import models.EmailableActions.EmailableActions
import play.api.{Configuration, Logger}
import services.SendEmailActor.NotifiableEvent

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object SendEmailActor {
  trait SEMsg

  //public messages to send to the actor
  case class NotifiableEvent(eventType:EmailableActions, messageParameters:Map[String,String], intiatingUser:UserProfile, overrideTemplate:Option[String]) extends SEMsg

  //public reply messages to handle
  case object NoEventAssociation extends SEMsg
  case object EventSent extends SEMsg
  case class DBError(errorString: String) extends SEMsg
  case class SESError(errorString:String) extends  SEMsg
  case class GeneralError(errorString:String) extends SEMsg
}


@Singleton
class SendEmailActor @Inject() (config:Configuration, SESClientManager: SESClientManager, emailTemplateAssociationDAO: EmailTemplateAssociationDAO) extends Actor {
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val logger = Logger(getClass)
  protected val sesClient = SESClientManager.getClient(awsProfile)

  import SendEmailActor._

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
  override def receive: Receive = {
    case NotifiableEvent(eventType, messageParameters, initiatingUser, maybeOverrideTemplate)=>
      val originalSender = sender()

      getRightTemplateName(eventType, maybeOverrideTemplate).map({
        case Left(errMsg)=>
          originalSender ! errMsg
        case Right(templateName)=>
          val maybeRequest = Try {
            new SendTemplatedEmailRequest()
              .withTemplate(templateName)
              .withTemplateData(messageParameters.asJson.toString())
              .withDestination(new Destination().withToAddresses(initiatingUser.userEmail))
              .withReplyToAddresses(config.get[String]("email.replyToAddress"))
              .withSource(config.get[String]("email.sourceAddress"))
          }

          maybeRequest match {
            case Success(rq) =>
              val result = Try {
                sesClient.sendTemplatedEmail(rq)
              }
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

  }
}
