package com.theguardian.multimedia.archivehunter.common

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import com.sksamuel.elastic4s.{Hit, HitReader}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{MediaMetadataMapConverters, ProblemItem, ProxyVerifyResult}
import org.apache.logging.log4j.LogManager

trait ProblemItemHitReader extends MediaMetadataMapConverters {
  private val ownLogger = LogManager.getLogger(getClass)

  private def mapToResult(data:Map[String,Any]):ProxyVerifyResult = {
    ownLogger.warn(data)
    ProxyVerifyResult(
      data("fileId").asInstanceOf[String],
      ProxyType.withName(data("proxyType").asInstanceOf[String]),
      data("wantProxy").asInstanceOf[Boolean],
      data("esRecordSays").asInstanceOf[Boolean],
      Option(data.getOrElse("haveProxy", null)).map(value => value.asInstanceOf[Boolean])
    )
  }

  implicit object ProblemItemHR extends HitReader[ProblemItem] {
    override def read(hit: Hit): Either[Throwable, ProblemItem] = {

      try {
        Right(
          ProblemItem(
            hit.sourceField("fileId").asInstanceOf[String],
            Option(hit.sourceField("collection")).getOrElse("unknown").asInstanceOf[String],
            hit.sourceField("filePath").asInstanceOf[String],
            hit.sourceField("esRecordSays").asInstanceOf[Boolean],
            hit.sourceField("verifyResults").asInstanceOf[Seq[Map[String,Any]]].map(entry=>mapToResult(entry))
          )
        )

      } catch {
        case ex:Throwable=>
          Left(ex)
      }
    }
  }
}
