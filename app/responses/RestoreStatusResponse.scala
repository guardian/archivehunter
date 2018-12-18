package responses

import java.time.ZonedDateTime

import com.theguardian.multimedia.archivehunter.common.cmn_models.RestoreStatus

case class RestoreStatusResponse(status:String, fileId: String, restoreStatus:RestoreStatus.Value, expiry:Option[ZonedDateTime])
