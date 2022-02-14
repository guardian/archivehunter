package models
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

trait LifecycleMessageDecoder {
  implicit val encodeLifecycleMessage: Encoder[LifecycleMessage] = new Encoder[LifecycleMessage] {
    override def apply(a: LifecycleMessage): Json = Json.obj(
      ("version", Json.fromString(a.version.toString)),
      ("id", Json.fromString(a.id)),
      ("detail-type", Json.fromString(a.detailType)),
      ("source", Json.fromString(a.source)),
      ("account", Json.fromString(a.account)),
      ("time", Json.fromString(a.time.format(DateTimeFormatter.ISO_DATE_TIME))),
      ("region", Json.fromString(a.region)),
      ("resources", Json.arr(a.resources.map(Json.fromString):_*)),
      ("detail", a.detail.map(_.asJson).getOrElse(Json.Null)
    ))
  }

  implicit val decodeLifecycleMessage: Decoder[LifecycleMessage] = new Decoder[LifecycleMessage] {
    override def apply(c: HCursor): Result[LifecycleMessage] = {
      for {
        versionString <- c.downField("version").as[String]
        id <- c.downField("id").as[String]
        detailType <- c.downField("detail-type").as[String]
        source <- c.downField("source").as[String]
        account <- c.downField("account").as[String]
        timeStr <- c.downField("time").as[String]
        regionStr <- c.downField("region").as[String]
        resources <- c.downField("resources").as[List[String]]
        detail <- c.downField("detail").as[Option[LifecycleDetails]]
      } yield LifecycleMessage(
        versionString.toInt,
        id,
        detailType,
        source,
        account,
        ZonedDateTime.parse(timeStr, DateTimeFormatter.ISO_DATE_TIME),
        regionStr,
        resources,
        detail
      )
    }
  }
}
