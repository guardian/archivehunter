package models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo.{DynamoFormat, ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.StorageClass
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import javax.inject.{Inject, Singleton}
import models.EmailableActions.EmailableActions
import play.api.Configuration

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success

@Singleton
class EmailTemplateAssociationDAO @Inject() (config:Configuration, dynamoClientManager: DynamoClientManager)(implicit system:ActorSystem) extends EmailableActionsEncoder {
  import com.gu.scanamo.syntax._


  protected val awsProfile = config.getOptional[String]("externalData.awsProfile")
  implicit val mat:Materializer = ActorMaterializer.create(system)
  protected val dynamoClient = dynamoClientManager.getNewAlpakkaDynamoClient(awsProfile)

  private val table = Table[EmailTemplateAssociation](config.get[String]("email.templateAssociationTableName"))

  private val templateNameIndex = table.index("templateNameIndex")

  /**
    * save a new, or update an existing, record to the table
    * @param newRecord record to save
    * @return a Future with either a Right or a Left to indicate success/failure
    */
  def put(newRecord:EmailTemplateAssociation) = ScanamoAlpakka.exec(dynamoClient)(table.put(newRecord)).map({
    case None=>Right(None)
    case Some(Right(record))=>Right(Some(record))
    case Some(Left(err))=>Left(err)
  })

  /**
    * look up the email association for the given action
    * @param actionName
    * @return
    */
  def getByAction(actionName:EmailableActions) = ScanamoAlpakka.exec(dynamoClient)(table.get('emailableAction->actionName.toString))

  /**
    * query the template name secondary index
    * @param templateName
    * @return
    */
  def getByTemplateName(templateName:String) =
    ScanamoAlpakka.exec(dynamoClient)(templateNameIndex.query('templateName->templateName)).map(resultList=>{
      val failures = resultList.collect({case Left(err)=>err})
      if(failures.nonEmpty){
        Left(failures)
      } else {
        Right(resultList.collect({ case Right(rec)=>rec }))
      }
    })

  /**
    * remove any existing associations connected to the given template name
    * @param templateName
    * @return
    */
  def removeExistingForTemplate(templateName:String) = {
    getByTemplateName(templateName).map(_.map(currentTemplates=>currentTemplates.map(tpl=>delete(tpl.emailableAction))))
  }

  /**
    * return a list of all the records. If any fail, then the operation as a whole fails and you get a list of errors
    * @return
    */
  def scanAll = ScanamoAlpakka.exec(dynamoClient)(table.scan()).map(results=>{
    val failures = results.collect({case Left(err)=>err})
    if(failures.nonEmpty){
      Left(failures)
    } else {
      Right(results.collect({ case Right(rec) => rec }))
    }
  })

  /**
    * delete the given association for the table
    * @param actionName
    * @return
    */
  def delete(actionName:EmailableActions) = ScanamoAlpakka.exec(dynamoClient)(table.delete('emailableAction->actionName.toString))
}
