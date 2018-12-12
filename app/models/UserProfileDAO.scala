package models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.gu.scanamo.syntax._
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class UserProfileDAO @Inject() (config:Configuration, ddbClientMgr:DynamoClientManager)(implicit actorSystem:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat {
  protected val tableName = config.get[String]("auth.userProfileTable")

  private val table = Table[UserProfile](tableName)
  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

  def userProfileForEmail(userEmail:String) = ScanamoAlpakka.exec(ddbClient)(table.get('userEmail -> userEmail))

  def put(userProfile: UserProfile) = ScanamoAlpakka.exec(ddbClient)(table.put(userProfile))

  def allUsers() = ScanamoAlpakka.exec(ddbClient)(table.scan())
}
