package models

import models.EmailableActions.EmailableActions

case class EmailTemplateAssociation (emailableAction: EmailableActions, templateName: String, setBy:String)