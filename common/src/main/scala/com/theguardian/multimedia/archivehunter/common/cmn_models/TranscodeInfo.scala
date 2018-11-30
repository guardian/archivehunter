package com.theguardian.multimedia.archivehunter.common.cmn_models

import com.theguardian.multimedia.archivehunter.common.ProxyType

case class TranscodeInfo(transcodeId:String,destinationBucket:String,proxyType:ProxyType.Value)
