package com.theguardian.multimedia.archivehunter.common.cmn_models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, ComparisonOperator, Condition, QueryRequest}
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LightboxEntryDAO @Inject()(config:ArchiveHunterConfiguration, ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with RestoreStatusEncoder {
  import com.gu.scanamo.syntax._

  private val logger = LoggerFactory.getLogger(getClass)
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

  def sourceForStatus(status:RestoreStatus.Value)(implicit ec:ExecutionContext) = {
//    val request = new QueryRequest()
//        .withTableName(config.get[String]("lightbox.tableName"))
//        .withIndexName("statusIndex")
//        .withKeyConditions(Map("restoreStatus"->new Condition()
//          .withComparisonOperator(ComparisonOperator.EQ)
//          .withAttributeValueList(List(new AttributeValue().withS(status.toString)).asJava)
//        ).asJava)
//    DynamoDb.source(request)
  }

  def put(entry:LightboxEntry)(implicit ec:ExecutionContext) =
    ScanamoAlpakka.exec(apClient)(table.put(entry))
}
