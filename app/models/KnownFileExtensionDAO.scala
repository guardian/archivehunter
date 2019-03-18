package models

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.ProxyTypeEncoder
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import javax.inject.Inject
import play.api.Configuration

import scala.concurrent.ExecutionContext

class KnownFileExtensionDAO @Inject() (config:Configuration, dynamoClientManager: DynamoClientManager) (implicit actorSystem:ActorSystem) extends ProxyTypeEncoder {
  import com.gu.scanamo.syntax._

  private implicit val mat:ActorMaterializer = ActorMaterializer.create(actorSystem)
  private implicit val ec:ExecutionContext = actorSystem.dispatcher
  protected val tableName = config.get[String]("externalData.knownFileTypesTable")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val ddbClient = dynamoClientManager.getNewAlpakkaDynamoClient(awsProfile)

  private val table = Table[KnownFileExtension](tableName)

  def put(entry:KnownFileExtension) = ScanamoAlpakka.exec(ddbClient)(table.put(entry))

  def get(forExtension:String) = ScanamoAlpakka.exec(ddbClient)(table.get('extension->forExtension))

  def getAll = ScanamoAlpakka.exec(ddbClient)(table.scan()).map(results=>{
    val failures = results.collect({case Left(err)=>err})
    if(failures.nonEmpty){
      Left(failures)
    } else {
      Right(results.collect({case Right(entry)=>entry}))
    }
  })


}
