package com.theguardian.multimedia.archivehunter.common

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.DeleteItemResult
import com.gu.scanamo.query.UniqueKey
import com.gu.scanamo.{DynamoFormat, Scanamo, ScanamoAlpakka, Table}
import com.gu.scanamo.syntax._
import javax.inject.Inject

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ProxyLocationDAO @Inject() (config:ArchiveHunterConfiguration) extends ProxyLocationEncoder {
  import com.gu.scanamo.syntax._

  val tableName = config.get[String]("proxies.tableName")
  val table = Table[ProxyLocation](tableName)
  val proxyIdIndex = table.index("proxyIdIndex")

  /**
    * Look up a proxy by main media ID and proxy type. This is the main method of locating proxies.
    * @param fileId ID of the main media, same as appears in the ES index
    * @param proxyType [[ProxyType]] value that indentifies the type of proxy we are looking for
    * @param client implicitly provided (alpakka) DynamoDB client
    * @return a Future that contains an Option. The Option is empty if no proxy is available or populated if one was found.
    *         the future fails on a DB error; use recoverWith to process this.
    */
  def getProxy(fileId: String, proxyType: ProxyType.Value)(implicit client:DynamoClient):Future[Option[ProxyLocation]] =
    ScanamoAlpakka.exec(client)(table.get('fileId->fileId and ('proxyType->proxyType.toString))).map({
      case Some(Left(err))=>throw new RuntimeException(err.toString)
      case Some(Right(location))=>Some(location)
      case None=>None
    })

  /**
    * Look up proxy by proxy ID
    * @param proxyId proxyID to look up
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future that contains an Optioon. The Option is empty if no proxy is availalble or populated if one was found.
    *         the future fails on a DB error; use recoverWith to process this.
    */
  def getProxyByProxyId(proxyId:String)(implicit client:DynamoClient):Future[Option[ProxyLocation]] =
    ScanamoAlpakka.exec(client)(proxyIdIndex.query('proxyId->proxyId)).map(_.map({
      case Left(err)=>throw new RuntimeException(err.toString)
      case Right(location)=>location
    })).map(_.headOption) //the index should ensure that there is only one id with this value


  /**
    * Write the given [[ProxyLocation]] record to the database.  This will over-write any existing record with the same IDs.
    * @param proxy [[ProxyLocation]] record to write
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future containing an Option with either a DB error or a record.  Either None or Some(Right) indicates success.
    */
  def saveProxy(proxy: ProxyLocation)(implicit client:DynamoClient) =
    ScanamoAlpakka.exec(client)(
      table.put(proxy)
    )

  /**
    * Delete the given [[ProxyLocation]] record by proxy ID.  This requires an initial lookup and then deletion on the main
    * table's key
    * @param proxyId proxy ID to delete
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future containing either an error string or a DeleteItemResult.
    */
  def deleteProxyRecord(proxyId:String)(implicit client:DynamoClient):Future[Either[String,DeleteItemResult]] =
    ScanamoAlpakka.exec(client)(
      proxyIdIndex.query('proxyId->proxyId)
    ).flatMap(results=>{
      val errors = results.collect({case Left(err)=>err})
      if(errors.nonEmpty){
        Future(Left(errors.head.toString))
      } else {
        if(results.length>1){
          Future(Left("Multiple proxy IDs present"))
        } else {
          val success = results.collect({case Right(result)=>result})
          ScanamoAlpakka.exec(client)(
            table.delete('fileId->success.head.fileId and ('proxyType->success.head.proxyType))
          ).map(result=>Right(result))
        }
      }
    })

  /**
    * Delete the give [[ProxyLocation]] record by main master ID and proxy type.
    * @param fileId main media ID for the proxy location to delete
    * @param proxyType proxy type to delete
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future containing a DeleteItemResult
    */
  def deleteProxyRecord(fileId:String, proxyType:ProxyType.Value)(implicit client:DynamoClient) =
    ScanamoAlpakka.exec(client)(
      table.delete('fileId->fileId and ('proxyType->proxyType.toString))
    )
}
