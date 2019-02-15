package com.theguardian.multimedia.archivehunter.common

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}

import com.sksamuel.elastic4s.{Hit, HitReader}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{MediaMetadata, MediaMetadataMapConverters, StreamDisposition}
import org.apache.logging.log4j.LogManager

trait ArchiveEntryHitReader extends MediaMetadataMapConverters {
  private val ownLogger = LogManager.getLogger(getClass)

  private def mappingToMimeType(value:Map[String,String]) =
    MimeType(value("major"),value("minor"))

  private def mappingToLightboxes(value:Seq[Map[String,String]]) =
    value.map(entry=>
      LightboxIndex(entry("owner"),
        //if you don't do this, an original value of None becomes Some(null), which is not pleasant...
        entry.get("avatarUrl").flatMap({
          case null=>None
          case other=>Some(other)
        }),
        ZonedDateTime.parse(entry("addedAt"), DateTimeFormatter.ISO_DATE_TIME),
        entry.get("memberOfBulk"))
    )

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
          Option(hit.sourceField("region").asInstanceOf[String]),
          Option(hit.sourceField("file_extension").asInstanceOf[String]),
          size,
          timestamp,
          hit.sourceField("etag").asInstanceOf[String],
          mappingToMimeType(hit.sourceField("mimeType").asInstanceOf[Map[String,String]]),
          hit.sourceField("proxied").asInstanceOf[Boolean],
          StorageClass.withName(hit.sourceField("storageClass").asInstanceOf[String]),
          mappingToLightboxes(hit.sourceField("lightboxEntries").asInstanceOf[Seq[Map[String,String]]]),
          hit.sourceField("beenDeleted").asInstanceOf[Boolean],
          hit.sourceFieldOpt("mediaMetadata").asInstanceOf[Option[Map[String,AnyVal]]] match {
            case None=>None
            case Some(null)=>None
            case Some(other)=>
              try {
                Some(mappingToMediaMetadata(other))
              } catch {
                case err:ClassCastException=>
                  ownLogger.error(s"Class Cast exception converting metadata for ${hit.sourceField("id").asInstanceOf[String]}: ",err)
                  None
              }
          }
        ))
      } catch {
        case ex:Throwable=>
          Left(ex)
      }
    }
  }
}
