package controllers

import java.time.{ZoneId, ZoneOffset, ZonedDateTime}

import akka.actor.ActorSystem
import com.amazonaws.services.simpleemail.model.{DeleteTemplateRequest, ListTemplatesRequest, TemplateMetadata}
import com.theguardian.multimedia.archivehunter.common.ProxyLocationDAO
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager, SESClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.JobModelDAO
import helpers.{InjectableRefresher, SESHelper}
import javax.inject.Inject
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectGetResponse, ObjectListResponse}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.MDC
import requests.UpdateEmailTemplate

class EmailAdminController @Inject() (override val config:Configuration,
                                      override val controllerComponents:ControllerComponents,
                                      override val refresher:InjectableRefresher,
                                      override val wsClient:WSClient,
                                      sesClientManager:SESClientManager)
                                     (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with Circe with PanDomainAuthActions with AdminsOnly {

  protected val sesClient = sesClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))
  private val sesHelper = new SESHelper(sesClient)

  private val logger = Logger(getClass)

  /**
    * list out the available email templates
    * @return
    */
  def listEmailTemplates = APIAuthAction { request=>
    adminsOnlySync(request) {
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

      val templateDataListResult = Try { getNextPage(None, Seq()) }

      templateDataListResult match {
        case Success(resultList)=>
          val resultsToSend = resultList.map(templateMetadataEntry=>Map(
            "name"->templateMetadataEntry.getName,
            "timestamp"->templateMetadataEntry.getCreatedTimestamp.toString
          ))
          Ok(ObjectListResponse("ok","emailTemplate", resultsToSend, resultsToSend.length).asJson)
        case Failure(exception)=>
          logger.error("Could not list email templates: ", exception)
          InternalServerError(GenericErrorResponse("error",exception.toString).asJson)
      }
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
}
