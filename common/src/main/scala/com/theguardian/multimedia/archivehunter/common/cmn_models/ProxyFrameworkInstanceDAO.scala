package com.theguardian.multimedia.archivehunter.common.cmn_models

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import org.scanamo.syntax._
import org.scanamo.{DynamoReadError, ScanamoAlpakka, Table}
import org.scanamo.generic.auto._
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class ProxyFrameworkInstanceDAO @Inject()(config:ArchiveHunterConfiguration, dynamoClientMgr:DynamoClientManager)(implicit system:ActorSystem, mat:Materializer){
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")

  private val scanamoAlpakka = ScanamoAlpakka(dynamoClientMgr.getNewAsyncDynamoClient(awsProfile))

  private val tableName = config.get[String]("proxyFramework.trackingTable")
  private val table = Table[ProxyFrameworkInstance](tableName)

  private val makeProxyFrameworkInstanceSink = Sink.fold[List[Either[DynamoReadError, ProxyFrameworkInstance]], List[Either[DynamoReadError, ProxyFrameworkInstance]]](List())(_ ++ _)

  def put(record:ProxyFrameworkInstance) = scanamoAlpakka
    .exec(table.put(record))
    .runWith(Sink.head)
    .map(_=>record)

  def allRecords = scanamoAlpakka
    .exec(table.scan)
    .runWith(makeProxyFrameworkInstanceSink)

  def recordsForRegion(rgn:String) = scanamoAlpakka
    .exec(table.query("region"===rgn))
    .runWith(makeProxyFrameworkInstanceSink)

  def remove(rgn:String) = scanamoAlpakka
    .exec(table.delete("region"===rgn))
    .runWith(Sink.head)
}
