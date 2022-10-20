package models

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import org.scanamo.{DynamoReadError, ScanamoAlpakka, Table}
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat

import javax.inject.{Inject, Singleton}
import play.api.Configuration

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class UserProfileDAO @Inject() (config:Configuration, ddbClientMgr:DynamoClientManager)(implicit actorSystem:ActorSystem, mat:Materializer)
  extends ZonedDateTimeEncoder with ZonedTimeFormat {
  protected val tableName = config.get[String]("auth.userProfileTable")

  private val table = Table[UserProfile](tableName)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val scanamoAlpakka = ScanamoAlpakka(ddbClientMgr.getNewAsyncDynamoClient(awsProfile))

  private val MakeUserProfileSink = Sink.fold[List[Either[DynamoReadError, UserProfile]], List[Either[DynamoReadError, UserProfile]]](List())(_ ++ _)
  def userProfileForEmail(userEmail:String) = scanamoAlpakka
    .exec(table.get("userEmail"===userEmail))
    .runWith(Sink.head)

  def put(userProfile: UserProfile) = scanamoAlpakka
    .exec(table.put(userProfile))
    .runWith(Sink.head)
    .map(_=>userProfile)

  def allUsers() = scanamoAlpakka
    .exec(table.scan())
    .runWith(MakeUserProfileSink)

  def delete(userEmail:String) = scanamoAlpakka
    .exec(table.delete("userEmail"===userEmail))
    .runWith(Sink.head)
}
