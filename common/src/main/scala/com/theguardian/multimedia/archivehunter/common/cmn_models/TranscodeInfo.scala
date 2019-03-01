package com.theguardian.multimedia.archivehunter.common.cmn_models

import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.RequestType
import com.theguardian.multimedia.archivehunter.common.ProxyType

case class TranscodeInfo(destinationBucket:String,region:String, proxyType:Option[ProxyType.Value], requestType:RequestType.Value)
