package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import java.time.ZonedDateTime

import scala.util.Try
import scala.xml.NodeSeq

object xmp extends ((Option[ZonedDateTime], Option[ZonedDateTime],Option[ZonedDateTime])=>xmp) with xmpextractor[xmp] {
  override def fromXml(node: NodeSeq): Try[xmp] = Try {
    new xmp(
      extract(node \ "xmp:CreateDate").map(ZonedDateTime.parse(_)),
      extract(node \ "xmp:MetadataDate").map(ZonedDateTime.parse(_)),
      extract(node \ "xmp:ModifyDate").map(ZonedDateTime.parse(_))
    )
  }
}

case class xmp (createDate:Option[ZonedDateTime], metadataDate:Option[ZonedDateTime], modifyDate:Option[ZonedDateTime])