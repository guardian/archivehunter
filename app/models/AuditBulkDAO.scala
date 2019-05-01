package models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import javax.inject.Inject
import play.api.{Configuration, Logger}
import io.circe.generic.auto._

class AuditBulkDAO @Inject() (config:Configuration, esClientMgr:ESClientManager)(implicit actorSystem:ActorSystem)
  extends ZonedDateTimeEncoder with AuditEntryClassEncoder with ApprovalStatusEncoder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  private val logger=Logger(getClass)
  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

  private implicit val esClient = esClientMgr.getClient()

  //implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  def saveSingle(auditBulk: AuditBulk) = esClient.execute {
    indexInto(indexName) doc auditBulk
  }

  def lookupForLightboxBulk(lbBulkId:String) = esClient.execute {
    search(indexName) matchQuery("lightboxBulkId.keyword", lbBulkId)
  }
}
