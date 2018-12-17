package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

object xmpDuration extends ((Long, Option[String])=>xmpDuration) with xmpextractor[xmpDuration] {
  override def fromXml(node:NodeSeq) = Try {
    new xmpDuration(extract(node \ "xmpDM:value").get.toLong, extract(node \ "xmpDM:scale"))
  }
}

case class xmpDuration (duration:Long, scale:Option[String])