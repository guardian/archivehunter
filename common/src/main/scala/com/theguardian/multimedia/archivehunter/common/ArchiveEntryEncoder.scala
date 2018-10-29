package com.theguardian.multimedia.archivehunter.common

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

trait ArchiveEntryEncoder extends MimeTypeEncoder  with ZonedDateTimeEncoder {
  lazy implicit val archiveEntryEncoder: Encoder[ArchiveEntry] = deriveEncoder[ArchiveEntry]
  lazy implicit val archiveEntryDecoder: Decoder[ArchiveEntry] = deriveDecoder[ArchiveEntry]
}
