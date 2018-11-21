package com.theguardian.multimedia.archivehunter.common

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.gu.scanamo.query.UniqueKey
import com.gu.scanamo.{DynamoFormat, Scanamo, ScanamoAlpakka, Table}
import com.gu.scanamo.syntax._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ProxyLocationDAO (tableName:String) extends ProxyLocationEncoder {
  import com.gu.scanamo.syntax._

  val table = Table[ProxyLocation](tableName)
  val proxyIdIndex = table.index("proxyIdIndex")

  def getProxy(fileId: String, proxyType: ProxyType.Value)(implicit client:AmazonDynamoDBAsync):Future[Option[ProxyLocation]] = Future {
    Scanamo.exec(client)(table.get('fileId->fileId and ('proxyType->proxyType.toString))).map({
      case Left(err)=>throw new RuntimeException(err.toString)
      case Right(location)=>location
    })
  }

  def getProxyByProxyId(proxyId:String)(implicit client:DynamoClient):Future[Option[ProxyLocation]] =
    ScanamoAlpakka.exec(client)(proxyIdIndex.query('proxyId->proxyId)).map(_.map({
      case Left(err)=>throw new RuntimeException(err.toString)
      case Right(location)=>location
    })).map(_.headOption) //the index should ensure that there is only one id with this value


  def saveProxy(proxy: ProxyLocation)(implicit client:DynamoClient) =
    ScanamoAlpakka.exec(client)(
      table.put(proxy)
    )

}
