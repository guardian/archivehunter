package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

object xmpDM extends ((Option[String],Option[String],Option[String],Option[String],Option[String],Option[xmpFrameSize],Option[xmpDuration])=>xmpDM) with xmpextractor[xmpDM] {
  def fromXml(node:NodeSeq) = Try {
    new xmpDM(extract(node \ "xmpDM:videoPixelAspectRatio"),
      extract(node \ "xmpDM:videoFrameRate"),
      extract(node \ "xmpDM:videoCompressor"),
      extract(node \ "xmpDM:audioCompressor"),
      extract(node \ "xmpDM:cameraModel"),
      xmpFrameSize.fromXmlOption(node \ "xmpDM:videoFrameSize"),
      xmpDuration.fromXmlOption(node \ "xmpDM:duration"))
  }
}

case class xmpDM (videoPixelAspectRatio:Option[String], videoFrameRate:Option[String], videoCompressor:Option[String],
                  audioCompressor:Option[String], cameraModel:Option[String], videoFrameSize:Option[xmpFrameSize],
                  duration:Option[xmpDuration])