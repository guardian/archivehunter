package responses

import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxBulkEntry

case class BulkDownloadInitiateResponse(status:String, metadata:LightboxBulkEntry, retrievalToken:String, entries:Seq[String])
