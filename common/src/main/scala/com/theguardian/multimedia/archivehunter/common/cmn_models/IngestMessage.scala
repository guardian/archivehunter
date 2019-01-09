package com.theguardian.multimedia.archivehunter.common.cmn_models

import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, MimeType}

case class IngestMessage (archiveEntry:ArchiveEntry, ingestTaskId:String)
