package models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LightboxEntryDAO @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with RestoreStatusEncoder {
  import com.gu.scanamo.syntax._

  private val logger = Logger(getClass)
  private implicit val mat:Materializer = ActorMaterializer.create(system)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val lightboxTableName = config.get[String]("lightbox.tableName")
  private val apClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

  protected val table = Table[LightboxEntry](lightboxTableName)
  protected val statusIndex = table.index("statusIndex")

  def get(userEmail:String, fileId:String)(implicit ec:ExecutionContext):Future[Option[Either[DynamoReadError, LightboxEntry]]] =
    ScanamoAlpakka.exec(apClient)(table.get('userEmail->userEmail and ('fileId->fileId)))

  def delete(userEmail:String, fileId:String)(implicit ec:ExecutionContext) =
    ScanamoAlpakka.exec(apClient)(table.delete('userEmail->userEmail and ('fileId->fileId)))

  def allForUser(userEmail:String)(implicit ec:ExecutionContext):Future[Seq[Either[DynamoReadError, LightboxEntry]]] =
    ScanamoAlpakka.exec(apClient)(table.query('userEmail->userEmail))

  def allForStatus(status:RestoreStatus.Value)(implicit ec:ExecutionContext) =
    ScanamoAlpakka.exec(apClient)(statusIndex.query('restoreStatus->status.toString))

  def put(entry:LightboxEntry)(implicit ec:ExecutionContext) =
    ScanamoAlpakka.exec(apClient)(table.put(entry))
}
