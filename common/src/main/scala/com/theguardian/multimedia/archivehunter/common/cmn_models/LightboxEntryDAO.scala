package com.theguardian.multimedia.archivehunter.common.cmn_models

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import org.scanamo.{DynamoReadError, ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat

import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import org.scanamo.syntax._
import org.scanamo.generic.auto._

import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LightboxEntryDAO @Inject()(config:ArchiveHunterConfiguration, ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with RestoreStatusEncoder {

  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val mat:Materializer = ActorMaterializer.create(system)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val lightboxTableName = config.get[String]("lightbox.tableName")
  private val scanamoAlpakka = ScanamoAlpakka(ddbClientMgr.getNewAsyncDynamoClient(awsProfile))

  protected val table = Table[LightboxEntry](lightboxTableName)
  protected val statusIndex = table.index("statusIndex")

  private val MakeLightboxEntrySink = Sink.fold[List[Either[DynamoReadError, LightboxEntry]], List[Either[DynamoReadError, LightboxEntry]]](List())(_ ++ _)

  def get(userEmail:String, fileId:String)(implicit ec:ExecutionContext):Future[Option[Either[DynamoReadError, LightboxEntry]]] =
    scanamoAlpakka
      .exec(table.get("userEmail"===userEmail and ("fileId"===fileId)))
      .runWith(Sink.head)

  def delete(userEmail:String, fileId:String)(implicit ec:ExecutionContext) =
    scanamoAlpakka
      .exec(table.delete("userEmail"===userEmail and ("fileId"===fileId)))
      .runWith(Sink.head)

  def allForUser(userEmail:String)(implicit ec:ExecutionContext):Future[Seq[Either[DynamoReadError, LightboxEntry]]] =
    scanamoAlpakka
      .exec(table.query("userEmail"===userEmail))
      .runWith(MakeLightboxEntrySink)

  def allForStatus(status:RestoreStatus.Value)(implicit ec:ExecutionContext) =
    scanamoAlpakka
      .exec(statusIndex.query("restoreStatus"===status.toString))
      .runWith(MakeLightboxEntrySink)

  def sourceForStatus(status:RestoreStatus.Value)(implicit ec:ExecutionContext) =
    Source.fromGraph(
      scanamoAlpakka.exec(statusIndex.query("restoreStatus"===status.toString))
    )

  def put(entry:LightboxEntry)(implicit ec:ExecutionContext) =
    scanamoAlpakka
      .exec(table.put(entry))
      .runWith(Sink.head)
      .map(_=>entry)
}
