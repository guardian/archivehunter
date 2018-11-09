package com.theguardian.multimedia.archivehunter.common

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.gu.scanamo.query.UniqueKey
import com.gu.scanamo.{DynamoFormat, Scanamo, Table}
import com.gu.scanamo.syntax._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ProxyLocationDAO (tableName:String) extends StorageClassEncoder {
  implicit val proxyTypeFormat = DynamoFormat.coercedXmap[ProxyType.Value,String,IllegalArgumentException](
    input=>ProxyType.withName(input)
  )(
    pt=>pt.toString
  )

  val table = Table[ProxyLocation](tableName)

  def getProxy(fileId: String, proxyType: ProxyType.Value)(implicit client:AmazonDynamoDBAsync):Future[Option[ProxyLocation]] = Future {
    Scanamo.exec(client)(table.get('fileId->fileId and ('proxyType->proxyType.toString))).map({
      case Left(err)=>throw new RuntimeException(err.toString)
      case Right(location)=>location
    })
  }
}
