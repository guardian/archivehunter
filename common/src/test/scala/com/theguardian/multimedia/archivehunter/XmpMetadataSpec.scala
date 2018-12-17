package com.theguardian.multimedia.archivehunter

import com.theguardian.multimedia.archivehunter.common.cmn_models.XmpMetadata
import org.specs2.mutable.Specification

import scala.io.Source
import scala.xml.parsing.ConstructingParser

class XmpMetadataSpec extends Specification {
  "XmpMetadata" should {
    "interpret a parsed XML file" in {
      val source = Source.fromURL(getClass.getResource("/Clip0038.MXF.xmp"))
      val parser = ConstructingParser.fromSource(source, preserveWS = false)

      val xmpMeta = XmpMetadata.fromXml(parser.document())

      println(xmpMeta)
      xmpMeta must beSuccessfulTry
      xmpMeta.get.dc.get.identifier must beSome("060A2B340101010501010D4313000000E8B481B0548005DAFCDBB3FFFED5CE7B")
    }
  }
}
