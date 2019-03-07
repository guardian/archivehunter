package models

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import javax.inject.Inject
import play.api.Configuration

import scala.concurrent.ExecutionContext

class ServerTokenDAO @Inject() (config:Configuration, ddbClientMgr:DynamoClientManager)(implicit actorSystem:ActorSystem) extends ZonedTimeFormat{
  import com.gu.scanamo.syntax._

  private implicit val mat:ActorMaterializer = ActorMaterializer.create(actorSystem)
  private implicit val ec:ExecutionContext = actorSystem.dispatcher
  protected val tableName = config.get[String]("serverToken.serverTokenTable")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

  private val table = Table[ServerTokenEntry](tableName)

  def put(entry:ServerTokenEntry) = ScanamoAlpakka.exec(ddbClient)(table.put(entry))

  def get(tokenValue:String) = ScanamoAlpakka.exec(ddbClient)(table.get('value->tokenValue))
}
