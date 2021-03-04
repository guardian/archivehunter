package com.theguardian.multimedia.archivehunter.common

import io.circe.{Decoder, Encoder}

object ProxyType extends Enumeration {
  type ProxyType = Value
  val VIDEO, AUDIO, THUMBNAIL,UNKNOWN = Value
}

trait ProxyTypeEncoder {
  implicit val proxyTypeEncoder = Encoder.encodeEnumeration(ProxyType)
  implicit val proxyTypeDecoder = Decoder.decodeEnumeration(ProxyType)
}