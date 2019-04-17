package models

import com.theguardian.multimedia.archivehunter.common.cmn_models.ProxyHealth

case class GroupedResult (fileId:String, esRecordSays:Boolean, result:ProxyHealth.Value)
