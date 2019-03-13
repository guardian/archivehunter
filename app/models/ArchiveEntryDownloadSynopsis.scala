package models

import com.theguardian.multimedia.archivehunter.common.ArchiveEntry

case class ArchiveEntryDownloadSynopsis(entryId:String, path:String, fileSize:Long)

object ArchiveEntryDownloadSynopsis extends ((String, String, Long)=>ArchiveEntryDownloadSynopsis) {
  def fromArchiveEntry(entry:ArchiveEntry) = {
    new ArchiveEntryDownloadSynopsis(entry.id, entry.path, entry.size)
  }
}