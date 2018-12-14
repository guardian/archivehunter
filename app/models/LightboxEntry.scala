package models

import java.time.ZonedDateTime

case class LightboxEntry (userEmail:String, fileId:String, addedAt: ZonedDateTime, restoreStatus:RestoreStatus.Value,
                          restoreStarted:Option[ZonedDateTime], restoreCompleted:Option[ZonedDateTime],
                          availableUntil:Option[ZonedDateTime], lastError: Option[String])
