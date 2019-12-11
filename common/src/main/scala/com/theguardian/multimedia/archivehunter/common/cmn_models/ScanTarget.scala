package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime

import com.gu.scanamo.DynamoFormat

/**
  * a ScanTarget represents a specific storage location for media and contains metadata about when it was scanned and where the proxies
  * should be located.
  * @param bucketName
  * @param enabled
  * @param lastScanned
  * @param scanInterval
  * @param scanInProgress
  * @param lastError
  * @param proxyBucket
  * @param region
  * @param pendingJobIds
  * @param transcoderCheck
  * @param paranoid
  */
case class ScanTarget (bucketName:String, enabled:Boolean, lastScanned:Option[ZonedDateTime], scanInterval:Long,
                       scanInProgress:Boolean, lastError:Option[String], proxyBucket:String, region:String,
                       pendingJobIds: Option[Seq[String]], transcoderCheck:Option[TranscoderCheck],
                       paranoid:Option[Boolean], proxyEnabled:Option[Boolean]) {
  /**
    * returns a new ScanTarget with the pendingJobIds updated to include the provided jobId
    * @param jobId
    * @return
    */
  def withAnotherPendingJob(jobId:String) = {
    val updatedJobsList = pendingJobIds match {
      case Some(existingSeq)=>existingSeq ++ Seq(jobId)
      case None=>Seq(jobId)
    }
    this.copy(pendingJobIds = Some(updatedJobsList))
  }

  /**
    * returns a new ScanTarget with the pendingJobIds updated to remove the provided jobId
    * @param jobId
    * @return
    */
  def withoutPendingJob(jobId:String) = {
    val updatedJobsList = pendingJobIds match {
      case Some(existingSeq)=>existingSeq.filter(_!=jobId)
      case None=>Seq()
    }
    val toSet = if(updatedJobsList.isEmpty) None else Some(updatedJobsList)
    this.copy(pendingJobIds = toSet)
  }
}
