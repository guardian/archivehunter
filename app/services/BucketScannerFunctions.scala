package services

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTarget
import play.api.{Configuration, Logger}

trait BucketScannerFunctions {
  val config:Configuration
  protected val logger:Logger

  /**
    * returns a boolean indicating whether the given target is due a scan, i.e. last_scan + scan_interval < now OR
    * not scanned at all
    * @param target [[ScanTarget]] instance to check
    * @return boolean flag
    */
  def scanIsScheduled(target: ScanTarget) = {
    target.lastScanned match {
      case None=>true
      case Some(lastScanned)=>
        logger.info(s"${target.bucketName}: Next scan is due at ${lastScanned.plus(target.scanInterval,ChronoUnit.SECONDS)}")
        lastScanned.plus(target.scanInterval,ChronoUnit.SECONDS).isBefore(ZonedDateTime.now())
    }
  }

  /**
    * returns a boolean indicating whether we should consider that a scan is in progress.
    * if there is a scan in progress but it's onlder than "scanner.staleAge" in the config then we run anyway
    * @param scanTarget
    * @return boolean indicating if a scan is in progress. True if so (i.e. don't rescan) or False.
    */
  def scanIsInProgress(scanTarget:ScanTarget) =
    if (scanTarget.scanInProgress) {
      scanTarget.lastScanned match {
        case Some(lastScanTime) =>
          val oneDay = 86400  //1 day in seconds
          val oldest = config.getOptional[Int]("scanner.staleAge").getOrElse(2) * oneDay

          val interval = ZonedDateTime.now().toInstant.getEpochSecond - lastScanTime.toInstant.getEpochSecond

          if (interval > oldest) {
            logger.warn(s"Current scan is ${interval/oneDay} days old, assuming that it is stale. Rescanning anyway.")
            false
          } else {
            true
          }
        case None=>
          true
      }
    } else {
      false
    }

}
