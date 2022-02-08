package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime
import java.util.UUID

import org.scanamo.DynamoFormat
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.RequestType
import com.theguardian.multimedia.archivehunter.common.{ProxyType, StorageClassEncoder}
import io.circe.{Decoder, Encoder}

object JobStatus extends Enumeration {
  val ST_PENDING, ST_RUNNING, ST_SUCCESS, ST_ERROR, ST_CANCELLED, ST_WARNING = Value
}

object SourceType extends Enumeration {
  val SRC_MEDIA, SRC_PROXY, SRC_THUMBNAIL, SRC_SCANTARGET, SRC_GLOBAL = Value
}

case class JobModel (jobId:String, jobType:String, startedAt: Option[ZonedDateTime],
                     completedAt: Option[ZonedDateTime], jobStatus: JobStatus.Value,
                     log:Option[String], sourceId:String, transcodeInfo: Option[TranscodeInfo],
                     sourceType: SourceType.Value, lastUpdatedTS: Option[ZonedDateTime])

object JobModel extends ((String, String, Option[ZonedDateTime], Option[ZonedDateTime], JobStatus.Value, Option[String], String, Option[TranscodeInfo], SourceType.Value, Option[ZonedDateTime])=>JobModel) {
  /**
    * shortcut constructor that sets defaults for most options
    * @param jobType
    * @param sourceId
    * @param sourceType
    * @param startTimeOverride
    * @return a new JobModel instance
    */
  def newJob(jobType:String, sourceId:String, sourceType:SourceType.Value, startTimeOverride:Option[ZonedDateTime]=None) = {
    new JobModel(UUID.randomUUID().toString,
      jobType,
      Some(startTimeOverride.getOrElse(ZonedDateTime.now())),
      None,
      JobStatus.ST_PENDING,
      None,
      sourceId,
      None,
      sourceType,
      None
    )
  }
}


trait JobModelEncoder {
  implicit val jobStatusEncoder = Encoder.encodeEnumeration(JobStatus)
  implicit val jobStatusDecoder = Decoder.decodeEnumeration(JobStatus)

  implicit val jobStatusFormat = DynamoFormat.coercedXmap[JobStatus.Value,String,IllegalArgumentException](
    input=>JobStatus.withName(input),
    pt=>pt.toString
  )

  implicit val proxyTypeEncoder = Encoder.encodeEnumeration(ProxyType)
  implicit val proxyTypeDecoder = Decoder.decodeEnumeration(ProxyType)

  implicit val proxyTypeFormat = DynamoFormat.coercedXmap[ProxyType.Value, String, IllegalArgumentException](
    input=>ProxyType.withName(input),pt=>pt.toString)

  implicit val sourceTypeEncoder = Encoder.encodeEnumeration(SourceType)
  implicit val sourceTypeDecoder = Decoder.decodeEnumeration(SourceType)

  implicit val sourceTypeFormat = DynamoFormat.coercedXmap[SourceType.Value,String,IllegalArgumentException](
    input=>SourceType.withName(input),
    pt=>pt.toString
  )

  implicit val requestTypeEncoder = Encoder.encodeEnumeration(RequestType)
  implicit val requestTypeDecoder = Decoder.decodeEnumeration(RequestType)
  implicit val requestTypeFormat =
    DynamoFormat.coercedXmap[RequestType.Value,String,IllegalArgumentException](input=>RequestType.withName(input),_.toString)
}