package models

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import org.scanamo.{ScanamoAlpakka, Table}
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat

import javax.inject.Inject
import play.api.Configuration

import scala.concurrent.ExecutionContext

class ServerTokenDAO @Inject() (config:Configuration, ddbClientMgr:DynamoClientManager)(implicit actorSystem:ActorSystem, mat:Materializer) extends ZonedTimeFormat{
  private implicit val ec:ExecutionContext = actorSystem.dispatcher
  protected val tableName = config.get[String]("serverToken.serverTokenTable")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val scanamoAlpakka = ScanamoAlpakka(ddbClientMgr.getNewAsyncDynamoClient(awsProfile))

  private val table = Table[ServerTokenEntry](tableName)

  def put(entry:ServerTokenEntry) = scanamoAlpakka
    .exec(table.put(entry))
    .runWith(Sink.head)
    .map(_=>entry)

  def get(tokenValue:String) = scanamoAlpakka
    .exec(table.get("value"===tokenValue))
    .runWith(Sink.head)
}
