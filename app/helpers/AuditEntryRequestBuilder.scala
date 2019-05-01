package helpers

import com.sksamuel.elastic4s.bulk.BulkCompatibleDefinition
import com.sksamuel.elastic4s.http.ElasticDsl.index
import com.sksamuel.elastic4s.streams.RequestBuilder
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import models.{ApprovalStatusEncoder, AuditApproval, AuditEntry, AuditEntryClassEncoder}
import io.circe.generic.auto._

trait AuditEntryRequestBuilder extends ZonedDateTimeEncoder with ApprovalStatusEncoder with AuditEntryClassEncoder {
  val indexName:String
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._

  implicit val auditEntryRequestBuilder = new RequestBuilder[AuditEntry] {
    override def request(t: AuditEntry): BulkCompatibleDefinition = index(indexName,"auditEntry") doc(t)
  }
}
