package com.theguardian.multimedia.archivehunter.common.cmn_helpers

import org.specs2.mutable.Specification

import java.time.{ZoneId, ZonedDateTime}

class S3RestoreHeaderSpec extends Specification {
  "S3RestoreHeader.apply" should {
    "parse an example value from the documentation" in {
      val x = S3RestoreHeader("ongoing-request=\"false\", expiry-date=\"Fri, 21 Dec 2012 00:00:00 GMT\"")
      x must beASuccessfulTry(S3RestoreHeader(false,Some(ZonedDateTime.of(2012,12,21,0,0,0,0,ZoneId.of("Z")))))
    }

    "parse value indicataing ongoing request" in {
      val x = S3RestoreHeader("ongoing-request=\"true\"")
      x must beASuccessfulTry(S3RestoreHeader(true,None))
    }
  }
}
