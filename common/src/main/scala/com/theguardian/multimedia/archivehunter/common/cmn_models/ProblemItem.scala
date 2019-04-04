package com.theguardian.multimedia.archivehunter.common.cmn_models

case class ProblemItem(fileId:String, collection:String, filePath:String, verifyResults:Seq[ProxyVerifyResult])
