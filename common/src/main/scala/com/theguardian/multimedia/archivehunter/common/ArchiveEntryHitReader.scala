package com.theguardian.multimedia.archivehunter.common

import java.time.{Instant, ZoneId, ZonedDateTime}

import com.sksamuel.elastic4s.{Hit, HitReader}

trait ArchiveEntryHitReader {
  implicit object ArchiveEntryHR extends HitReader[ArchiveEntry] {
    override def read(hit: Hit): Either[Throwable, ArchiveEntry] = {
      try {
        val timestamp = ZonedDateTime.ofInstant(Instant.parse(hit.sourceField("last_modified").asInstanceOf[String]), ZoneId.systemDefault())
        Right(ArchiveEntry(
          hit.sourceField("id").asInstanceOf[String],
          hit.sourceField("bucket").asInstanceOf[String],
          hit.sourceField("path").asInstanceOf[String],
          hit.sourceField("file_extension").asInstanceOf[Option[String]],
          hit.sourceField("size").asInstanceOf[Long],
          timestamp,
          hit.sourceField("eTag").asInstanceOf[String],
          MimeType.fromString(hit.sourceField("mimeType").asInstanceOf[String]).right.get,  //any exception gets reported as an error by try/catch
          hit.sourceField("proxied").asInstanceOf[Boolean]
        ))
      } catch {
        case ex:Throwable=>
          Left(ex)
      }
    }
  }
}
