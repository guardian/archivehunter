package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

object xmpFrameSize extends ((Double, Double, Option[String])=>xmpFrameSize) with xmpextractor[xmpFrameSize] {
  def fromXml(node:NodeSeq) = Try {
    new xmpFrameSize(extract(node \ "stDim:w").map(_.toDouble).get,extract(node \ "stDim:h").map(_.toDouble).get, extract(node \ "stDim:unit"))
  }

}

case class xmpFrameSize (width:Double, height:Double, unit:Option[String])