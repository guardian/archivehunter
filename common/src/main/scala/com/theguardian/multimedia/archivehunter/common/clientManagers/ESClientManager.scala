package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.sksamuel.elastic4s.http.{ElasticClient, ElasticProperties, HttpClient}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters}

import javax.inject.{Inject, Singleton}

trait ESClientManager {
  def getClient():ElasticClient
}

@Singleton
class ESClientManagerImpl @Inject()(config:ArchiveHunterConfiguration) extends ESClientManager with ExtValueConverters {
  val esHost:String = config.get[String]("elasticsearch.hostname")
  val esPort:Int = config.getOptional[Int]("elasticsearch.port").getOrElse(443)
  val sslFlag:Boolean = config.getOptional[Boolean]("elasticsearch.ssl").getOrElse(false)

  def getClient() = ElasticClient(ElasticProperties(s"${if(sslFlag) "https" else "http"}://$esHost:$esPort"))
}