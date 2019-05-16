package models

object EmailableActions extends Enumeration {
  type EmailableActions = Value
  val AdminPendingNotification, UserRequestGrantedNotification, UserRequestRejectedNotification, UserMediaReadyNotification = Value
}
