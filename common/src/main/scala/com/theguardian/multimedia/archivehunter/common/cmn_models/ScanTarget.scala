package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime

import com.gu.scanamo.DynamoFormat

case class ScanTarget (bucketName:String, enabled:Boolean, lastScanned:Option[ZonedDateTime], scanInterval:Long,
                       scanInProgress:Boolean, lastError:Option[String], proxyBucket:String, region:String,
                       pendingJobIds: Option[Seq[String]], transcoderCheck:Option[TranscoderCheck], paranoid:Option[Boolean])
