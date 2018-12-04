package models

import java.net.URI

import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}

trait UriDecoder {
  implicit val uriDecoder:Decoder[URI] = new Decoder[URI] {
    override final def apply(c: HCursor): Result[URI] = {
      c.as[String].map(URI.create)
    }
  }

  implicit val uriEncoder:Encoder[URI] = new Encoder[URI] {
    override def apply(a: URI): Json = Json.fromString(a.toString)
  }
}
