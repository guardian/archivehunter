import java.time.ZonedDateTime
import java.time.temporal.{ChronoUnit, TemporalAdjuster, TemporalAdjusters, TemporalUnit}

import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTarget
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.{Configuration, Logger}
import services.BucketScannerFunctions

class BucketScannerFunctionsSpec extends Specification with Mockito {
  "BucketScannerFunctions.scanIsInProgress" should {
    "return false if there is no scan in progress" in {
      val toTest = new BucketScannerFunctions {
        override protected val logger = mock[Logger]
        override val config: Configuration = Configuration.empty
      }

      val testScanTarget = ScanTarget("testbucket",true,None,1234L, false, None,"proxybucket","region",None,None,None, proxyEnabled = Some(false))
      toTest.scanIsInProgress(testScanTarget) mustEqual false
    }
  }

  "return false if there appears to be a scan in progress that is too old" in {
    val toTest = new BucketScannerFunctions {
      override protected val logger = mock[Logger]
      override val config: Configuration = Configuration.from(Map(
        "scanner.staleAge"->4
      ))
    }

    val lastScanTime = ZonedDateTime.now().minus(5, ChronoUnit.DAYS)
    val testScanTarget = ScanTarget("testbucket",enabled=true,Some(lastScanTime),1234L, scanInProgress = true, None,"proxybucket","region",None,None,None, proxyEnabled = Some(false))
    toTest.scanIsInProgress(testScanTarget) mustEqual false

  }

  "return true if there is a scan in progress and it is not too old" in {
    val toTest = new BucketScannerFunctions {
      override protected val logger = mock[Logger]
      override val config: Configuration = Configuration.from(Map(
        "scanner.staleAge"->4
      ))
    }

    val lastScanTime = ZonedDateTime.now().minus(1, ChronoUnit.DAYS)
    val testScanTarget = ScanTarget("testbucket",enabled=true,Some(lastScanTime),1234L, scanInProgress = true, None,"proxybucket","region",None,None,None, proxyEnabled = Some(false))
    toTest.scanIsInProgress(testScanTarget) mustEqual true

  }
}
