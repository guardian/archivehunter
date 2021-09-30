package requests

import com.theguardian.multimedia.archivehunter.common.ProxyType.ProxyType

/**
  * Represents a request to add a specific proxy to the given item
  * @param itemId item ID to add the proxy to
  * @param proxyPath path to the proxy file, in the item's proxy bucket
  * @param proxyBucket optional string indicating the proxy's bucket. If this does not agree with the bucket expected for the
  *                    given item ID, then a 409 (Conflict) is returned
  * @param proxyType ProxyType enum value indicating which proxy this is to set
  * @param overwrite If set and true, allow over-writing an existing proxy. Otherwise a 409 (Conflict) is returned if the given
  *                  proxy type already exists on the item
  */
case class ProxyImportRequest(itemId:String, proxyPath:String, proxyBucket:Option[String], proxyType:ProxyType, overwrite:Option[Boolean])
