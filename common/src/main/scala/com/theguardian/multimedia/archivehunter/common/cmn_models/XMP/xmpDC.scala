package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

object xmpDC extends ((Option[String])=>xmpDC) with xmpextractor[xmpDC] {
  override def fromXml(node: NodeSeq): Try[xmpDC] = Try {
    new xmpDC(extract(node \\ "dc:identifier"))
  }
}
case class xmpDC (identifier:Option[String])
