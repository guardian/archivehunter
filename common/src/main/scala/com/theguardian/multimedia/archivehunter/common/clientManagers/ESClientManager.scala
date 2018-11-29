package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters}
import javax.inject.{Inject, Singleton}

trait ESClientManager {
  def getClient():HttpClient
}

@Singleton
class ESClientManagerImpl @Inject()(config:ArchiveHunterConfiguration) extends ESClientManager with ExtValueConverters {
  val esHost:String = config.get[String]("elasticsearch.hostname")
  val esPort:Int = config.get[Int]("elasticsearch.port")
  val sslFlag:Boolean = config.getOptional[Boolean]("elasticsearch.ssl").getOrElse(false)
  def getClient() = HttpClient(s"elasticsearch://$esHost:$esPort?ssl=$sslFlag")
}