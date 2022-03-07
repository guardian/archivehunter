package services.FileMove

import TestFileMove.AkkaTestkitSpecs2Support
import com.amazonaws.regions.Regions
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ImprovedLargeFileCopierSpec extends Specification {
  sequential

  "ImprovedLargeFileCopier.headSourceFile" should {
    "return file metadata about a known public file" in new AkkaTestkitSpecs2Support {
      val toTest = new ImprovedLargeFileCopier()
      implicit val pool = toTest.newPoolClientFlow(Regions.EU_WEST_1)
      val result = Await.result(toTest.headSourceFile(Regions.EU_WEST_1,None, "gnm-multimedia-cdn","interactive/speedtest/testmpfile.dat",None), 10.seconds)

     result must beSome(
       ImprovedLargeFileCopier.HeadInfo(
        "gnm-multimedia-cdn",
         "interactive/speedtest/testmpfile.dat",
         None,
         "Thu, 25 Apr 2019 09:39:03 GMT",
         10485760,
         Some("\"ee32e01c6f0941f94330fc994dc6f31d-2\""),
         "binary/octet-stream",
         None,
         None
       )
     )
    }
  }
}
