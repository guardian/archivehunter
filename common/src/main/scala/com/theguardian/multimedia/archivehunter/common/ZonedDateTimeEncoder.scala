package com.theguardian.multimedia.archivehunter.common

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder, HCursor, Json}

trait ZonedDateTimeEncoder {
  implicit val encodeZonedDateTime: Encoder[ZonedDateTime] = new Encoder[ZonedDateTime] {
    override def apply(a: ZonedDateTime): Json = Json.fromString(a.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
  }

  implicit val decodeZonedDateTime: Decoder[ZonedDateTime] = new Decoder[ZonedDateTime] {
    override def apply(c: HCursor): Result[ZonedDateTime] = for {
      str <- c.value.as[String]
    } yield ZonedDateTime.parse(str, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
  }
}
