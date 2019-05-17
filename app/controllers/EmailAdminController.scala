package controllers

import java.time.{ZoneId, ZoneOffset, ZonedDateTime}

import akka.actor.{ActorRef, ActorSystem}
import com.amazonaws.services.simpleemail.model.{DeleteTemplateRequest, ListTemplatesRequest, TemplateMetadata}
import com.theguardian.multimedia.archivehunter.common.ProxyLocationDAO
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager, SESClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.JobModelDAO
import helpers.{InjectableRefresher, SESHelper}
import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{ErrorListResponse, GenericErrorResponse, ObjectGetResponse, ObjectListResponse}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import io.circe.generic.auto._
import io.circe.syntax._
import models.{EmailTemplateAssociation, EmailTemplateAssociationDAO, EmailableActions, EmailableActionsEncoder}
import org.slf4j.MDC
import requests.{SendTestEmailRequest, UpdateEmailTemplate}
import akka.pattern.ask
import services.SendEmailActor
import services.SendEmailActor.EventSent

import scala.concurrent.Future
import scala.concurrent.duration._
@Singleton
class EmailAdminController @Inject() (override val config:Configuration,
                                      override val controllerComponents:ControllerComponents,
                                      override val refresher:InjectableRefresher,
                                      override val wsClient:WSClient,
                                      sesClientManager:SESClientManager,
                                      emailTemplateAssociationTableDAO:EmailTemplateAssociationDAO,
                                      @Named("sendEmailActor") sendEmailActor:ActorRef)
                                     (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with Circe with PanDomainAuthActions with AdminsOnly with EmailableActionsEncoder{

  protected val sesClient = sesClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))
  private val sesHelper = new SESHelper(sesClient)

  private val logger = Logger(getClass)

  private implicit val actorTimeout:akka.util.Timeout = 30 seconds

  def matchAssociation(allAssociations:List[EmailTemplateAssociation], templateName:String):Option[EmailTemplateAssociation] = allAssociations.find(_.templateName==templateName)

  /**
    * list out the available email templates
    * @return
    */
  def listEmailTemplates = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {
      def getNextPage(nextPageToken:Option[String], currentSequence:Seq[TemplateMetadata]):Seq[TemplateMetadata] = {
        val rq = new ListTemplatesRequest()
        val finalRq = nextPageToken match {
          case Some(token)=> rq.withNextToken(token)
          case None=> rq
        }

        val result = sesClient.listTemplates(finalRq)
        Option(result.getNextToken) match {
          case nextToken @ Some(_)=>getNextPage(nextToken, currentSequence ++ result.getTemplatesMetadata.asScala)
          case None=>currentSequence ++ result.getTemplatesMetadata.asScala
        }
      }

      val associationScanFuture = emailTemplateAssociationTableDAO.scanAll

      val templateDataListResult = Try { getNextPage(None, Seq()) }

      associationScanFuture.map({
        case Left(err)=>
          logger.error(s"Could not retrieve template associations: ${err.toString}")
          InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
        case Right(associationList)=>
          templateDataListResult match {
            case Success(resultList)=>
              val resultsToSend = resultList.map(templateMetadataEntry=>Map(
                "name"->Some(templateMetadataEntry.getName),
                "timestamp"->Some(templateMetadataEntry.getCreatedTimestamp.toString),
                "association"->matchAssociation(associationList,templateMetadataEntry.getName).map(_.emailableAction.toString)
              ))
              Ok(ObjectListResponse("ok","emailTemplate", resultsToSend, resultsToSend.length).asJson)
            case Failure(exception)=>
              logger.error("Could not list email templates: ", exception)
              InternalServerError(GenericErrorResponse("error",exception.toString).asJson)
          }
      })
    }
  }

  /**
    * create or update the given email template
    * @return
    */
  def setEmailTemplate = APIAuthAction(circe.json(2048)) { request=>
    adminsOnlySync(request) {
      request.body.as[UpdateEmailTemplate].fold(
        err=>
          BadRequest(GenericErrorResponse("bad_request", err.toString()).asJson),
        updateRequest=>
          sesHelper.upsertEmailTemplate(updateRequest) match {
            case Success(_)=>
              Ok(GenericErrorResponse("ok","template updated").asJson)
            case Failure(err)=>
              MDC.put("updateRequest", updateRequest.toString)
              logger.error("Could not create or update email template: ", err)
              InternalServerError(GenericErrorResponse("error", err.toString).asJson)
          }
      )
    }
  }

  /**
    * retrieve an existing template
    * @param templateName
    * @return
    */
  def getEmailTemplate(templateName:String) = APIAuthAction { request=>
    adminsOnlySync(request) {
      sesHelper.getEmailTemplate(templateName) match {
        case Success(Some(templateData))=>
          val mappedData = Map(
            "name"->templateData.getTemplateName,
            "subjectPart"->templateData.getSubjectPart,
            "textPart"->templateData.getTextPart,
            "htmlPart"->templateData.getHtmlPart
          )
          Ok(ObjectGetResponse("ok","emailTemplate",mappedData).asJson)
        case Success(None)=>
          NotFound(GenericErrorResponse("not_found", "No template could be found with this name").asJson)
        case Failure(err)=>
          InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      }
    }
  }

  /**
    * delete the given email template from the system
    * @param templateName
    * @return
    */
  def removeEmailTemplate(templateName:String) = APIAuthAction { request=>
    adminsOnlySync(request) {
      val rq = new DeleteTemplateRequest().withTemplateName(templateName)
      Try {
        sesClient.deleteTemplate(rq)
      } match {
        case Success(_)=>Ok(GenericErrorResponse("ok","template deleted").asJson)
        case Failure(err)=>
          MDC.put("templateName", templateName)
          logger.error("Could not delete email template: ", err)
          InternalServerError(GenericErrorResponse("error", err.toString).asJson)
      }
    }
  }

  def makeAssociation(forAction:String, templateName:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {
      Try {
        emailTemplateAssociationTableDAO.removeExistingForTemplate(templateName).flatMap({
          case Left(errList)=>
            errList.foreach(err=>logger.error(s"Could not remove existing association for $forAction on $templateName: ${err.toString}"))
            Future(InternalServerError(ErrorListResponse("db_error","Could not remove existing association", errList.map(_.toString)).asJson))
          case Right(_)=>
            val record = EmailTemplateAssociation(EmailableActions.withName(forAction), templateName, request.user.email)
            emailTemplateAssociationTableDAO.put(record).map({
              case Left(err) => InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
              case Right(_) => Ok(GenericErrorResponse("ok", "association updated").asJson)
            })
          })
      } match {
        case Success(result)=>result
        case Failure(err)=> Future(BadRequest(GenericErrorResponse("invalid_request",err.toString).asJson))
      }
    }
  }

  def removeAssociation(forTemplate:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request) {
      Try {
        emailTemplateAssociationTableDAO.removeExistingForTemplate(forTemplate).map(deleteItemResult => {
          Ok(GenericErrorResponse("ok", "association removed").asJson)
        })
      } match {
        case Success(result)=>result
        case Failure(err)=>Future(BadRequest(GenericErrorResponse("invalid_request", err.toString).asJson))
      }
    }
  }

  def sendTestEmail(templateName:String) = APIAuthAction.async(circe.json(2048)) { request=>
    adminsOnlyAsyncWithProfile(request) { userProfile=>
      request.body.as[SendTestEmailRequest] match {
        case Left(err)=>
          Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson))
        case Right(testRequest)=>
          val resultFuture =
            (sendEmailActor ? SendEmailActor.NotifiableEvent(testRequest.mockedAction,testRequest.templateParameters,userProfile, Some(templateName))).mapTo[SendEmailActor.SEMsg]

          resultFuture.map({
            case SendEmailActor.NoEventAssociation=>
              BadRequest(GenericErrorResponse("bad_request", s"No event association for ${testRequest.mockedAction}").asJson)
            case SendEmailActor.DBError(errMsg:String)=>
              InternalServerError(GenericErrorResponse("db_error", errMsg).asJson)
            case SendEmailActor.SESError(errMsg:String)=>
              InternalServerError(GenericErrorResponse("ses_error", errMsg).asJson)
            case SendEmailActor.GeneralError(errMsg:String)=>
              InternalServerError(GenericErrorResponse("general_error", errMsg).asJson)
            case SendEmailActor.EventSent=>
              Ok(GenericErrorResponse("ok","Event sent to admin user").asJson)
          })
      }

    }
  }
}
