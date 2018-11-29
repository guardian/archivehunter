package com.theguardian.multimedia.archivehunter.common

class ArchiveHunterConfigurationStatic(staticConfig:Map[String,String]) extends ArchiveHunterConfiguration {

  override def get[T](key: String)(implicit converter: String => T): T = converter(staticConfig(key))
  override def getOptional[T](key: String)(implicit converter: String => T): Option[T] = staticConfig.get(key).map(converter(_))
}
