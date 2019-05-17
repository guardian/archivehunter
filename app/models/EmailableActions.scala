package models

import com.gu.scanamo.DynamoFormat
import io.circe.Decoder

object EmailableActions extends Enumeration {
  type EmailableActions = Value
  val AdminPendingNotification, UserRequestGrantedNotification, UserRequestRejectedNotification, UserMediaReadyNotification = Value
}

trait EmailableActionsEncoder {
  implicit val emailableActionsFormat = DynamoFormat.coercedXmap[EmailableActions.EmailableActions, String, IllegalArgumentException](
    input=>EmailableActions.withName(input)
  ) (
    ac=>ac.toString
  )

  implicit val emailableActionsDecoder = Decoder.enumDecoder(EmailableActions)
}