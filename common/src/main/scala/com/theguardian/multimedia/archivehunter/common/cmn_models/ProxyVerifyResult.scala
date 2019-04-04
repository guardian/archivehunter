package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.theguardian.multimedia.archivehunter.common.ProxyType

case class ProxyVerifyResult(fileId:String, proxyType:ProxyType.Value, wantProxy:Boolean, haveProxy:Option[Boolean]=None) {

  /**
    * decodes the fileID to a tuple of (collectionName, filePath)
    * @return
    */
  def extractLocation:(String, String) = {
    val decoder = Base64.getDecoder
    val decoded = new String(decoder.decode(fileId), StandardCharsets.UTF_8)

    val parts = decoded.split(":")
    (parts.head, parts.tail.mkString(""))
  }
}
