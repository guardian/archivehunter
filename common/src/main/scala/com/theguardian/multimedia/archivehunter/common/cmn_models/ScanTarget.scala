package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime

case class ScanTarget (bucketName:String, enabled:Boolean, lastScanned:Option[ZonedDateTime], scanInterval:Long, scanInProgress:Boolean, lastError:Option[String], proxyBucket:String, paranoid:Option[Boolean])