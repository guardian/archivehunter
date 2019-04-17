package com.theguardian.multimedia.archivehunter.common

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import com.sksamuel.elastic4s.{Hit, HitReader}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{MediaMetadataMapConverters, ProblemItemCount, ProxyVerifyResult}
import org.apache.logging.log4j.LogManager

trait ProblemItemCountHitReader extends MediaMetadataMapConverters {
  private val ownLogger = LogManager.getLogger(getClass)

  /**
    * ProblemItemCount(scanStart:ZonedDateTime, scanFinish:Option[ZonedDateTime],
    * proxiedCount:Int, partialCount:Int, unProxiedCount:Int,
    * notNeededCount:Int, dotFile:Int, glacier:Int, grandTotal:Int)
    */
  implicit object ProblemItemCountHR extends HitReader[ProblemItemCount] {
    override def read(hit: Hit): Either[Throwable, ProblemItemCount] = {

      try {
        Right(
          ProblemItemCount(
            ZonedDateTime.parse(hit.sourceField("scanStart").asInstanceOf[String]),
            Option(hit.sourceField("scanFinish").asInstanceOf[String]).map(scanFinish=>ZonedDateTime.parse(scanFinish)),
            hit.sourceField("proxiedCount").asInstanceOf[Int],
            hit.sourceField("partialCount").asInstanceOf[Int],
            hit.sourceField("unProxiedCount").asInstanceOf[Int],
            hit.sourceField("notNeededCount").asInstanceOf[Int],
            hit.sourceField("dotFile").asInstanceOf[Int],
            hit.sourceField("glacier").asInstanceOf[Int],
            hit.sourceField("grandTotal").asInstanceOf[Int]
          )
        )

      } catch {
        case ex:Throwable=>
          Left(ex)
      }
    }
  }
}
