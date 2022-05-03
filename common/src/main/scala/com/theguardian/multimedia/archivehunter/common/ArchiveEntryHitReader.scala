package com.theguardian.multimedia.archivehunter.common

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}
import com.sksamuel.elastic4s.{Hit, HitReader}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{MediaMetadata, MediaMetadataMapConverters, StreamDisposition}
import org.apache.logging.log4j.LogManager

import scala.util.Try

trait ArchiveEntryHitReader extends MediaMetadataMapConverters {
  private val ownLogger = LogManager.getLogger(getClass)

  private def mappingToMimeType(value:Map[String,String]) =
    MimeType(value("major"),value("minor"))

  private def safeGetOptional(entry:Map[String,String], key:String) = entry.get(key).flatMap({
    case null=>None
    case other=>Some(other)
  })

  private def mappingToLightboxes(value:Seq[Map[String,String]]) =
    value.map(entry=>
      LightboxIndex(entry("owner"),
        //if you don't do this, an original value of None becomes Some(null), which is not pleasant...
        safeGetOptional(entry, "avatarUrl"),
        ZonedDateTime.parse(entry("addedAt"), DateTimeFormatter.ISO_DATE_TIME),
        safeGetOptional(entry, "memberOfBulk")
      )
    )

  implicit object ArchiveEntryHR extends HitReader[ArchiveEntry] {
    override def read(hit: Hit): Try[ArchiveEntry] = {
      val size = try {
        hit.sourceField("size").asInstanceOf[Long]
      } catch {
        case ex:java.lang.ClassCastException=>
          hit.sourceField("size").asInstanceOf[Int].toLong
      }

      Try {
        val timestamp = ZonedDateTime.parse(hit.sourceField("last_modified").asInstanceOf[String])
        ArchiveEntry(
          hit.sourceField("id").asInstanceOf[String],
          hit.sourceField("bucket").asInstanceOf[String],
          hit.sourceField("path").asInstanceOf[String],
          hit.sourceFieldOpt("maybeVersion").flatMap(v=>Option(v.asInstanceOf[String])),  //for some reason this keeps coming through as Some(null)
          hit.sourceFieldOpt("region").flatMap(v=>Option(v.asInstanceOf[String])),
          hit.sourceFieldOpt("file_extension").flatMap(v=>Option(v.asInstanceOf[String])),
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
          },
          hit.sourceFieldOpt("hasDeleteMarker").asInstanceOf[Option[Boolean]]
        )
      }
    }
  }
}
