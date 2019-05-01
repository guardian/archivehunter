package models

import io.circe.{Decoder, Encoder}

object AuditEntryClass extends Enumeration {
  val Restore, Download = Value
}

trait AuditEntryClassEncoder {
  implicit val encodeAuditEntryClass:Encoder[AuditEntryClass.Value] = Encoder.enumEncoder(AuditEntryClass)
  implicit val decodeAuditEntryClass:Decoder[AuditEntryClass.Value] = Decoder.enumDecoder(AuditEntryClass)
}