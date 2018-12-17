package com.theguardian.multimedia.archivehunter.common.cmn_models

import com.theguardian.multimedia.archivehunter.common.cmn_models.XMP._

import scala.util.Try
import scala.xml.NodeSeq

object XmpMetadata extends ((Option[xmpDC], Option[xmp], Option[xmpDM], Option[tiff], Option[aux], Option[xmpMM])=>XmpMetadata) with xmpextractor[XmpMetadata] {
  override def fromXml(node: NodeSeq): Try[XmpMetadata] = Try {
    new XmpMetadata(
      xmpDC.fromXmlOption(node),
      xmp.fromXmlOption(node),
      xmpDM.fromXmlOption(node),
      tiff.fromXmlOption(node),
      aux.fromXmlOption(node),
      xmpMM.fromXmlOption(node)
    )
  }
}

/**
  * this class represents XMP metadata gathered from a .xmp sidecar file
  * @param dc
  * @param xmp
  * @param xmpDM
  * @param tiff
  * @param aux
  * @param xmpMM
  */
case class XmpMetadata (dc:Option[xmpDC], xmp:Option[xmp], xmpDM:Option[xmpDM], tiff:Option[tiff],
                        aux:Option[aux], xmpMM:Option[xmpMM])