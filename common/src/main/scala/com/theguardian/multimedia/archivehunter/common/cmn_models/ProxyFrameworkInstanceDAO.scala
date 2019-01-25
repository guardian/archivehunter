package com.theguardian.multimedia.archivehunter.common.cmn_models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo.syntax._
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import javax.inject.{Inject, Singleton}

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class ProxyFrameworkInstanceDAO @Inject()(config:ArchiveHunterConfiguration, dynamoClientMgr:DynamoClientManager)(implicit system:ActorSystem){
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val mat:Materializer = ActorMaterializer.create(system)
  private val dynamoClient = dynamoClientMgr.getNewAlpakkaDynamoClient(awsProfile)

  private val tableName = config.get[String]("proxyFramework.trackingTable")
  private val table = Table[ProxyFrameworkInstance](tableName)

  def put(record:ProxyFrameworkInstance) = ScanamoAlpakka.exec(dynamoClient)(table.put(record))

  def allRecords = ScanamoAlpakka.exec(dynamoClient)(table.scan)

  def recordsForRegion(rgn:String) = ScanamoAlpakka.exec(dynamoClient)(table.query('region->rgn))

  def remove(rgn:String) = ScanamoAlpakka.exec(dynamoClient)(table.delete('region->rgn))
}
