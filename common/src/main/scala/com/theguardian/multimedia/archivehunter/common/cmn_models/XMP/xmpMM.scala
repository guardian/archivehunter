package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

object xmpMM extends ((Option[String],Option[String],Option[String])=>xmpMM) with xmpextractor[xmpMM] {
  def fromXml(xml:NodeSeq) = Try {
      new xmpMM(
        extract(xml \ "xmpMM:InstanceID"),
        extract(xml \ "xmpMM:DocumentID"),
        extract(xml \ "xmpMM:OriginalDocumentID")
      )
  }
}

case class xmpMM (instanceId: Option[String], documentId:Option[String], originalDocumentId:Option[String])