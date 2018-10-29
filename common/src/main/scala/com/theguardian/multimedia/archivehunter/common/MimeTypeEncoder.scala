package com.theguardian.multimedia.archivehunter.common

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

trait MimeTypeEncoder {
  lazy implicit val mimeTypeEncoder: Encoder[MimeType] = deriveEncoder[MimeType]
  lazy implicit val mimeTypeDecoder: Decoder[MimeType] = deriveDecoder[MimeType]
}
