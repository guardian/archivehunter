package com.theguardian.multimedia.archivehunter.common

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import com.sksamuel.elastic4s.{Hit, HitReader}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{MediaMetadataMapConverters, ProblemItem, ProxyVerifyResult}
import org.apache.logging.log4j.LogManager

trait ProblemItemHitReader extends MediaMetadataMapConverters {
  private val ownLogger = LogManager.getLogger(getClass)

  def mapToResult(data:Map[String,String]):ProxyVerifyResult =
    ProxyVerifyResult(data("fileId"),ProxyType.withName(data("proxyType")),
      if(data.getOrElse("wantProxy","false")=="true") true else false,
      Option(data.getOrElse("haveProxy", null)).map(str=>if(str=="true") true else false)
    )

  implicit object ProblemItemHR extends HitReader[ProblemItem] {
    override def read(hit: Hit): Either[Throwable, ProblemItem] = {

      try {
        val timestamp = ZonedDateTime.parse(hit.sourceField("last_modified").asInstanceOf[String])
        Right(
          ProblemItem(
            hit.sourceField("id").asInstanceOf[String],
            hit.sourceField("results").asInstanceOf[Seq[Map[String,String]]].map(entry=>mapToResult(entry))
          )
        )

      } catch {
        case ex:Throwable=>
          Left(ex)
      }
    }
  }
}
