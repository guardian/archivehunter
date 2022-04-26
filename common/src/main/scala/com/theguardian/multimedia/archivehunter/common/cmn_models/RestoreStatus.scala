package com.theguardian.multimedia.archivehunter.common.cmn_models

import org.scanamo.DynamoFormat
import io.circe.{Decoder, Encoder}

/**
  * enum to represent possible restore statuses for an item in the lightbox
  * unneeded - item is not in glacier so does not need restore
  * already - item is already restored by another user
  * pending - item will be restored
  * underway - restore request has been sent and is being processed
  * success - item has been restored
  * error - item could not be restored for some reason
  * expired - item was restored but is now archived again
  */
object RestoreStatus extends Enumeration {
  val RS_UNNEEDED,RS_ALREADY,RS_PENDING,RS_UNDERWAY,RS_SUCCESS,RS_ERROR,RS_EXPIRED=Value
}

trait RestoreStatusEncoder {
  implicit val restoreStatusEncoder = Encoder.encodeEnumeration(RestoreStatus)
  implicit val restoreStatusDecoder = Decoder.decodeEnumeration(RestoreStatus)

  implicit val restoreStatusFormat = DynamoFormat.coercedXmap[RestoreStatus.Value,String,IllegalArgumentException](
    input=>RestoreStatus.withName(input), rs=>rs.toString)
}