package com.theguardian.multimedia.archivehunter.common

case class ProxyLocation (fileId:String, proxyType: ProxyType.Value, bucketName:String, bucketPath:String, storageClass: StorageClass.Value)
