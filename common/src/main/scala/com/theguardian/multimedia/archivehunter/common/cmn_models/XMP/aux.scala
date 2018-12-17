package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

object aux extends ((Option[String])=>aux) with xmpextractor[aux] {
  override def fromXml(node: NodeSeq): Try[aux] = Try {
    new aux(extract(node \ "aux:SerialNumber"))
  }
}

case class aux (serialNumber: Option[String])
