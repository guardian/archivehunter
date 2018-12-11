package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime

import com.gu.scanamo.DynamoFormat
import com.theguardian.multimedia.archivehunter.common.{ProxyType, StorageClassEncoder}
import io.circe.{Decoder, Encoder}

object JobStatus extends Enumeration {
  val ST_PENDING, ST_RUNNING, ST_SUCCESS, ST_ERROR = Value
}

object SourceType extends Enumeration {
  val SRC_MEDIA, SRC_PROXY, SRC_THUMBNAIL, SRC_GLOBAL = Value
}

case class JobModel (jobId:String, jobType:String, startedAt: Option[ZonedDateTime], completedAt: Option[ZonedDateTime], jobStatus: JobStatus.Value, log:Option[String], sourceId:String, transcodeInfo: Option[TranscodeInfo], sourceType: SourceType.Value)

trait JobModelEncoder {
  implicit val jobStatusEncoder = Encoder.enumEncoder(JobStatus)
  implicit val jobStatusDecoder = Decoder.enumDecoder(JobStatus)

  implicit val jobStatusFormat = DynamoFormat.coercedXmap[JobStatus.Value,String,IllegalArgumentException](
    input=>JobStatus.withName(input)
  )(
    pt=>pt.toString
  )

  implicit val proxyTypeEncoder = Encoder.enumEncoder(ProxyType)
  implicit val proxyTypeDecoder = Decoder.enumDecoder(ProxyType)

  implicit val proxyTypeFormat = DynamoFormat.coercedXmap[ProxyType.Value, String, IllegalArgumentException](
    input=>ProxyType.withName(input)
  )(pt=>pt.toString)

  implicit val sourceTypeEncoder = Encoder.enumEncoder(SourceType)
  implicit val sourceTypeDecoder = Decoder.enumDecoder(SourceType)

  implicit val sourceTypeFormat = DynamoFormat.coercedXmap[SourceType.Value,String,IllegalArgumentException](
    input=>SourceType.withName(input)
  )(
    pt=>pt.toString
  )
}