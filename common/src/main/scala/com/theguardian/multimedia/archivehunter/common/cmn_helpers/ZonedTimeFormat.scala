package com.theguardian.multimedia.archivehunter.common.cmn_helpers

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import com.gu.scanamo.DynamoFormat

/**
  * Scanamo-compatible formatter for ZonedDateTime. You need to mix this in when talking to Dynamo for ScanTargets
  */
trait ZonedTimeFormat {
  implicit val zonedTimeFormat = DynamoFormat.coercedXmap[ZonedDateTime, String, IllegalArgumentException](
    ZonedDateTime.parse(_)
  )(
    _.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
  )
}
