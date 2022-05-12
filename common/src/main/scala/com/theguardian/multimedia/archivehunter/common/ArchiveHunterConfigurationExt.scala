package com.theguardian.multimedia.archivehunter.common


//implementation of ArchiveHunterConfiguration that does not depend on Play
class ArchiveHunterConfigurationExt extends ArchiveHunterConfiguration {
  private val environmentKeyMapping = Map(
    "elasticsearch.index"->"INDEX_NAME",
    "elasticsearch.ssl"->"ELASTICSEARCH_SSL",
    "elasticsearch.port"->"ELASTICSEARCH_PORT",
    "externalData.awsProfile"->"AWS_PROFILE",
    "elasticsearch.hostname"->"ES_HOST_NAME",
    "proxies.tableName" -> "PROXIES_TABLE_NAME",
    "instances.tableName" -> "INSTANCES_TABLE",
    "externalData.jobTable" -> "JOB_TABLE",
    "lightbox.tableName" -> "LIGHTBOX_TABLE",
  )

  override def getOptional[T](key: String)(implicit converter: String=>T): Option[T] = {
    environmentKeyMapping.get(key) match {
      case None=>None
      case Some(envVar)=>
        sys.env.get(envVar).map(converter(_))
    }
  }

  override def get[T](key: String)(implicit converter: String=>T): T = getOptional[T](key) match {
    case None=>throw new RuntimeException(s"No configuration available for $key")
    case Some(value)=>value
  }

}
