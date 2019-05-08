package models

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.sksamuel.elastic4s.RefreshPolicy
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import javax.inject.Inject
import play.api.{Configuration, Logger}
import io.circe.generic.auto._

import scala.concurrent.ExecutionContext.Implicits.global

class AuditBulkDAO @Inject() (config:Configuration, esClientMgr:ESClientManager)(implicit actorSystem:ActorSystem)
  extends ZonedDateTimeEncoder with AuditEntryClassEncoder with ApprovalStatusEncoder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  private val logger=Logger(getClass)
  val indexName = config.getOptional[String]("externalData.auditBulkIndexName").getOrElse("auditbulk")

  private implicit val esClient = esClientMgr.getClient()

  def saveSingle(auditBulk: AuditBulk) = esClient.execute {
    //indexInto(indexName, "auditbulk") doc auditBulk id auditBulk.bulkId.toString refresh RefreshPolicy.WAIT_UNTIL
    update(auditBulk.bulkId.toString).in(s"$indexName/auditbulk") docAsUpsert auditBulk refresh RefreshPolicy.WAIT_UNTIL
  }

  def lookupForLightboxBulk(lbBulkId:String) = esClient.execute {
    search(indexName) matchQuery("lightboxBulkId.keyword", lbBulkId)
  }

  def lookupByUuid(uuid:UUID) = esClient.execute {
    //search(indexName) matchQuery("bulkId.keyword", uuid.toString)
    get(indexName, "auditbulk", uuid.toString)
  }.map(_.map(success=>{
      if(success.result.found)
        Some(success.result.to[AuditBulk])
      else
        None
  }))

  def searchForStatus(status:ApprovalStatus.Value) = esClient.execute {
    search(indexName) matchQuery("approvalStatus.keyword", status.toString)
  }.map(_.map(_.result.to[AuditBulk]))
}
