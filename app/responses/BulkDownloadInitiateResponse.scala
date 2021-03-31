package responses

import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxBulkEntry
import models.ArchiveEntryDownloadSynopsis

case class BulkDownloadInitiateResponse(status:String, metadata:LightboxBulkEntry, retrievalToken:String, entries:Option[Seq[ArchiveEntryDownloadSynopsis]])
