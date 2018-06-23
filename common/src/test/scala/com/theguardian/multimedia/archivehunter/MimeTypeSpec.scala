package com.theguardian.multimedia.archivehunter
import com.theguardian.multimedia.archivehunter.common.MimeType
import org.specs2.mutable.Specification

class MimeTypeSpec extends Specification {
  "MimeType.fromString" should {
    "return a valid MimeType in a Right for a valid string" in {
      val mt=MimeType.fromString("application/xml")
      mt must beRight(MimeType("application","xml"))
    }

    "return a Left if the string is not a valid MIME type" in {
      MimeType.fromString("fsdfdsfsfsfs") must beLeft("fsdfdsfsfsfs does not look like a MIME type")
      MimeType.fromString("application/something/notmime") must beLeft("application/something/notmime does not look like a MIME type")
    }
  }
}
