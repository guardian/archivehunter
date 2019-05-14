package helpers

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model.{CreateTemplateRequest, GetTemplateRequest, Template, UpdateTemplateRequest}
import requests.UpdateEmailTemplate

import scala.util.{Failure, Success, Try}

/**
  * helper functions for communicating with SES
  * @param sesClient
  */
class SESHelper (sesClient:AmazonSimpleEmailService) {
  /**
    * look up email template data of the given name
    * @param templateName
    * @return
    */
  def getEmailTemplate(templateName:String):Try[Option[Template]] = Try {
    val rq = new GetTemplateRequest().withTemplateName(templateName)
    val result = sesClient.getTemplate(rq)
    Option(result.getTemplate)
  } match {
    case success @ Success(_)=>success
    case Failure(err:com.amazonaws.services.simpleemail.model.TemplateDoesNotExistException)=>Success(None)
    case otherError @ Failure(_)=>otherError
  }


  /**
    * either create or update the given template.  This involves first getting an existing template, if any, then making
    * either a create or update request
    * @param updateRequest populated UpdateEmailTemplate case class
    * @return a Try containing either success metadata from AWS or an exception indicating error
    */
  def upsertEmailTemplate(updateRequest:UpdateEmailTemplate) = Try {
    val existingTemplateTry = getEmailTemplate(updateRequest.name)
    existingTemplateTry.flatMap({
      case Some(existingTemplate)=>
        val updatedTemplate = existingTemplate.clone()
          .withHtmlPart(updateRequest.htmlPart)
          .withTextPart(updateRequest.textPart)
          .withSubjectPart(updateRequest.subjectPart)

        val rq = new UpdateTemplateRequest().withTemplate(updatedTemplate)
        Try { sesClient.updateTemplate(rq)}
      case None=>
        val template = new Template().withTemplateName(updateRequest.name)
          .withHtmlPart(updateRequest.htmlPart)
          .withSubjectPart(updateRequest.subjectPart)
          .withTextPart(updateRequest.textPart)

        val rq = new CreateTemplateRequest().withTemplate(template)

        Try { sesClient.createTemplate(rq) }
    })
  }.flatten
}
