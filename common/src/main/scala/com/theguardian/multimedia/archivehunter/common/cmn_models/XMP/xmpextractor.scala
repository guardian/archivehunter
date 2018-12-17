package com.theguardian.multimedia.archivehunter.common.cmn_models.XMP

import scala.util.Try
import scala.xml.NodeSeq

trait xmpextractor[A] {
  def fromXml(node:NodeSeq):Try[A]

  def fromXmlOption(node:NodeSeq):Option[A] = fromXml(node).fold(err=>None, result=>Some(result))

  def extract(node:NodeSeq) = {
    if(node.isEmpty) None else Some(node.text)
  }
}
