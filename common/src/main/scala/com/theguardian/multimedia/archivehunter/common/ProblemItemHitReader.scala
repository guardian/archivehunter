package com.theguardian.multimedia.archivehunter.common

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import com.sksamuel.elastic4s.{Hit, HitReader}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{MediaMetadataMapConverters, ProblemItem, ProxyVerifyResult}
import org.apache.logging.log4j.LogManager

trait ProblemItemHitReader extends MediaMetadataMapConverters {
  private val ownLogger = LogManager.getLogger(getClass)

  private def mapToResult(data:Map[String,Any]):ProxyVerifyResult =
    ProxyVerifyResult(data("fileId").asInstanceOf[String],ProxyType.withName(data("proxyType").asInstanceOf[String]),
      if(data.getOrElse("wantProxy","false")=="true") true else false,
      Option(data.getOrElse("haveProxy", null)).map(value=>value.asInstanceOf[Boolean])
    )

  implicit object ProblemItemHR extends HitReader[ProblemItem] {
    override def read(hit: Hit): Either[Throwable, ProblemItem] = {

      try {
        Right(
          ProblemItem(
            hit.sourceField("fileId").asInstanceOf[String],
            hit.sourceField("collection").asInstanceOf[String],
            hit.sourceField("filePath").asInstanceOf[String],
            hit.sourceField("verifyResults").asInstanceOf[Seq[Map[String,String]]].map(entry=>mapToResult(entry))
          )
        )

      } catch {
        case ex:Throwable=>
          Left(ex)
      }
    }
  }
}
