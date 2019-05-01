package models

import io.circe.{Decoder, Encoder}

object ApprovalStatus extends Enumeration {
  val Pending, Allowed, Rejected, Queried = Value
}

trait ApprovalStatusEncoder {
  implicit val encodeApprovalStatus:Encoder[ApprovalStatus.Value] = Encoder.enumEncoder(ApprovalStatus)
  implicit val decodeApprovalStatus:Decoder[ApprovalStatus.Value] = Decoder.enumDecoder(ApprovalStatus)
}