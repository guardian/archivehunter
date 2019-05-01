package models

import java.time.ZonedDateTime
import java.util.UUID

import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxBulkEntry

object AuditBulk extends ((UUID, String, String, ApprovalStatus.Value, String, ZonedDateTime, String, Option[AuditApproval])=>AuditBulk)
{
  def fromLightboxBulk(lbBulkEntry:LightboxBulkEntry, forPath:String, requestor:String, reasonGiven:String, requestTime:Option[ZonedDateTime]=None) = {
    new AuditBulk(UUID.randomUUID(), lbBulkEntry.id, forPath, ApprovalStatus.Pending, requestor, requestTime.getOrElse(ZonedDateTime.now()), reasonGiven, None)
  }
}

case class AuditBulk (bulkId:UUID, lighboxBulkId:String, basePath:String, approvalStatus:ApprovalStatus.Value, requestedBy:String,
                      requestedAt:ZonedDateTime, reasonGiven:String, approval: Option[AuditApproval])
