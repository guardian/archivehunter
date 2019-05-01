package models

import java.time.ZonedDateTime

import com.theguardian.multimedia.archivehunter.common.ArchiveEntry

object AuditEntry extends ((String, Long, String, String, ZonedDateTime, String, AuditEntryClass.Value, Option[String])=>AuditEntry) {
  def fromArchiveEntry(entry:ArchiveEntry, requestor:String, entryClass: AuditEntryClass.Value, forBulk:Option[String], createdAtOverride:Option[ZonedDateTime] = None) = {
    new AuditEntry(entry.id, entry.size, entry.region.getOrElse("unknown"), requestor, createdAtOverride.getOrElse(ZonedDateTime.now()), entry.bucket, entryClass, forBulk)
  }
}

case class AuditEntry (fileId:String, fileSize:Long, region:String, requestedBy:String, createdAt:ZonedDateTime, forCollection:String, entryClass: AuditEntryClass.Value, forBulk:Option[String])