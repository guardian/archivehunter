package com.theguardian.multimedia.archivehunter.common.cmn_models

import com.theguardian.multimedia.archivehunter.common.ProxyType

case class ProblemItem(fileId:String, collection:String, filePath:String, esRecordSays: Boolean, verifyResults:Seq[ProxyVerifyResult], decision:Option[ProxyHealth.Value]) {
  def copyExcludingResult(proxyType:ProxyType.Value) = this.copy(verifyResults = verifyResults.filter(_.proxyType!=proxyType))
}