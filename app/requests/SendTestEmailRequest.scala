package requests

import models.EmailableActions.EmailableActions

//templateName is specified in the request url
case class SendTestEmailRequest (mockedAction:EmailableActions, templateParameters:Map[String,String])
