package requests

import com.theguardian.multimedia.archivehunter.common.ProxyType

case class ManualProxySet (entryId:String, proxyBucket:String, proxyPath:String, proxyType:ProxyType.Value)