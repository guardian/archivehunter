package com.theguardian.multimedia.archivehunter.common.cmn_models

import io.circe.{Decoder, Encoder}

object ProxyHealth extends Enumeration {
  val Proxied, Partial, Unproxied, NotNeeded, DotFile, GlacierClass = Value
}

trait ProxyHealthEncoder {
  implicit val proxyResultEncoder = Encoder.enumEncoder(ProxyHealth)
  implicit val proxyResultDecoder = Decoder.enumDecoder(ProxyHealth)
}