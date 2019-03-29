package models

import com.theguardian.multimedia.archivehunter.common.ProxyType

case class ProxyVerifyResult(fileId:String, proxyType:ProxyType.Value, wantProxy:Boolean, haveProxy:Option[Boolean]=None)