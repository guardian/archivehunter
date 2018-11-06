package com.theguardian.multimedia.archivehunter.common

import java.time.{Instant, ZoneId, ZonedDateTime}

import com.sksamuel.elastic4s.{Hit, HitReader}

trait ArchiveEntryHitReader {
  private def mappingToMimeType(value:Map[String,String]) =
    MimeType(value("major"),value("minor"))
  implicit object ArchiveEntryHR extends HitReader[ArchiveEntry] {
    override def read(hit: Hit): Either[Throwable, ArchiveEntry] = {
      val size = try {
        hit.sourceField("size").asInstanceOf[Long]
      } catch {
        case ex:java.lang.ClassCastException=>
          hit.sourceField("size").asInstanceOf[Int].toLong
      }

      try {
        val timestamp = ZonedDateTime.parse(hit.sourceField("last_modified").asInstanceOf[String])
        Right(ArchiveEntry(
          hit.sourceField("id").asInstanceOf[String],
          hit.sourceField("bucket").asInstanceOf[String],
          hit.sourceField("path").asInstanceOf[String],
          Option(hit.sourceField("file_extension").asInstanceOf[String]),
          size,
          timestamp,
          hit.sourceField("etag").asInstanceOf[String],
          mappingToMimeType(hit.sourceField("mimeType").asInstanceOf[Map[String,String]]),
          hit.sourceField("proxied").asInstanceOf[Boolean],
          StorageClass.withName(hit.sourceField("storageClass").asInstanceOf[String]),
          hit.sourceField("beenDeleted").asInstanceOf[Boolean]
        ))
      } catch {
        case ex:Throwable=>
          Left(ex)
      }
    }
  }
}
