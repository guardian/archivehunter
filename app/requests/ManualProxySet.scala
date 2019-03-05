package requests

import com.theguardian.multimedia.archivehunter.common.ProxyType

case class ManualProxySet (entryId:String, proxyBucket:String, proxyPath:String, region:String, proxyType:ProxyType.Value)