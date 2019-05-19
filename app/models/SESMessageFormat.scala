package models

import java.time.ZonedDateTime

import io.circe.{Decoder, Encoder}

object SESEventType extends Enumeration {
  type SESEventType=Value
  val Bounce,Send=Value
}

trait SESEncoder {
  implicit val sesEventTypeDecoder = Decoder.enumDecoder(SESEventType)
  implicit val sesEventTypeEncoder = Encoder.enumEncoder(SESEventType)
}

case class SESCommonHeader(from:Seq[String], replyTo:Seq[String], date:String, to:Seq[String], messageId:String, subject:String)

case class SESRecipientInfo(emailAddress:String, action: String, status: String, diagnosticCode:Option[String])
case class SESBounce(bounceType:String, bounceSubType:String, bouncedRecipients:Seq[SESRecipientInfo], timestamp:ZonedDateTime,feedbackId:Option[String],reportingMTA:Option[String])
case class SESHeader(name:String, value:String)
case class SESMail(timestamp:ZonedDateTime, source:String,sourceArn:Option[String],sendingAccountId:Option[String], messageId:String, destination:Seq[String], headersTruncated:Option[Boolean], headers:Seq[SESHeader], commonHeaders:Option[SESCommonHeader], tags:Map[String, Seq[String]])

case class SESMessageFormat (eventType:SESEventType.Value, bounce:Option[SESBounce], mail:Option[SESMail])