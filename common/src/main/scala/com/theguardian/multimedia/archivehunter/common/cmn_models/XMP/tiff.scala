package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

object tiff extends ((Option[String],Option[String])=>tiff) with xmpextractor[tiff] {
  override def fromXml(node: NodeSeq): Try[tiff] = Try {
    new tiff(extract(node \ "tiff:Model"), extract(node \ "tiff:Make"))
  }
}
case class tiff (model:Option[String],make:Option[String])

