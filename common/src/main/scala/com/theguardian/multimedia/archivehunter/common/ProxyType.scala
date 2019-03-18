package com.theguardian.multimedia.archivehunter.common

import com.gu.scanamo.DynamoFormat
import io.circe.{Decoder, Encoder}

object ProxyType extends Enumeration {
  type ProxyType = Value
  val VIDEO, AUDIO, THUMBNAIL,UNKNOWN = Value
}

trait ProxyTypeEncoder {
  implicit val proxyTypeEncoder = Encoder.enumEncoder(ProxyType)
  implicit val proxyTypeDecoder = Decoder.enumDecoder(ProxyType)

  implicit val proxyTypeFormat = DynamoFormat.coercedXmap[ProxyType.Value, String, IllegalArgumentException](
    input=>ProxyType.withName(input)
  )(pt=>pt.toString)
}