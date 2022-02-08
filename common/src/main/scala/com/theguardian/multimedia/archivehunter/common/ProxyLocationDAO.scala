package com.theguardian.multimedia.archivehunter.common

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbClient}
import org.scanamo.{DynamoFormat, DynamoReadError, Scanamo, ScanamoAlpakka, Table}
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import org.slf4j.LoggerFactory

import javax.inject.Inject
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ProxyLocationDAO @Inject() (config:ArchiveHunterConfiguration)(implicit mat:Materializer) extends ProxyLocationEncoder {
  import org.scanamo.syntax._
  private val logger = LoggerFactory.getLogger(getClass)

  val tableName = config.get[String]("proxies.tableName")
  val table = Table[ProxyLocation](tableName)
  val proxyIdIndex = table.index("proxyIdIndex")

  private val MakeProxyLocationSink = Sink.fold[List[Either[DynamoReadError, ProxyLocation]], List[Either[DynamoReadError, ProxyLocation]]](List())(_ ++ _)

  /**
    * Look up a proxy by main media ID and proxy type. This is the main method of locating proxies.
    * @param fileId ID of the main media, same as appears in the ES index
    * @param proxyType [[ProxyType]] value that indentifies the type of proxy we are looking for
    * @param client implicitly provided (alpakka) DynamoDB client
    * @return a Future that contains an Option. The Option is empty if no proxy is available or populated if one was found.
    *         the future fails on a DB error; use recoverWith to process this.
    */
  def getProxy(fileId: String, proxyType: ProxyType.Value)(implicit client:DynamoDbAsyncClient):Future[Option[ProxyLocation]] =
    ScanamoAlpakka(client).exec(table.get("fileId" === fileId and "proxyType"===proxyType.toString)).runWith(Sink.head).map({
      case Some(Left(err))=>throw new RuntimeException(err.toString)
      case Some(Right(location))=>Some(location)
      case None=>None
    })

  /**
    * Synchronous (non-Akka) version of getProxy, used in streams.
    * @param fileId
    * @param proxyType
    * @param client
    * @return
    */
  def getProxySync(fileId:String, proxyType:ProxyType.Value)(implicit client:DynamoDbClient) =
    Scanamo(client).exec(table.get("fileId"===fileId and "proxyType"===proxyType.toString))

  def getAllProxiesFor(fileId:String)(implicit client:DynamoDbAsyncClient) =
    ScanamoAlpakka(client).exec(table.query("fileId"===fileId)).runWith(MakeProxyLocationSink)

  /**
    * Look up proxy by proxy ID
    * @param proxyId proxyID to look up
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future that contains an Option. The Option is empty if no proxy is availalble or populated if one was found.
    *         the future fails on a DB error; use recoverWith to process this.
    */
  def getProxyByProxyId(proxyId:String)(implicit client:DynamoDbAsyncClient):Future[Option[ProxyLocation]] = {
    ScanamoAlpakka(client)
      .exec(proxyIdIndex.query("proxyId"===proxyId))
      .runWith(Sink.headOption)
      .map({
        case Some(results)=>
          val failures = results.collect({case Left(err)=>err})
          if(failures.nonEmpty) {
            throw new RuntimeException(s"Could not query for proxy ID $proxyId - ${failures.map(_.toString).mkString(";")}")
          } else {
            results.collectFirst { case Right(result) => result }
          }
        case None=>
          None
      })
  }

  /**
    * Write the given [[ProxyLocation]] record to the database.  This will over-write any existing record with the same IDs.
    * @param proxy [[ProxyLocation]] record to write
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future containing an Option with either a DB error or a record.  Either None or Some(Right) indicates success.
    */
  def saveProxy(proxy: ProxyLocation)(implicit client:DynamoDbAsyncClient) =
    ScanamoAlpakka(client)
      .exec(table.put(proxy))
      .runWith(Sink.head)
      .map(_=>proxy)

  /**
    * Delete the given [[ProxyLocation]] record by proxy ID.  This requires an initial lookup and then deletion on the main
    * table's key
    * @param proxyId proxy ID to delete
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future containing either an error string or a DeleteItemResult.
    */
  def deleteProxyRecord(proxyId:String)(implicit client:DynamoDbAsyncClient):Future[Either[String,Unit]] = {
    val resultFut = for {
      maybeRecord <- ScanamoAlpakka(client).exec(proxyIdIndex.query("proxyId" === proxyId)).runWith(Sink.head)
      deleteResults <- Future.sequence(maybeRecord.map({
        case Left(err)=>
          throw new RuntimeException(err.toString)
        case Right(location)=>
          ScanamoAlpakka(client)
            .exec(table.delete("fileId"===location.fileId and "proxyType"===location.proxyType))
            .runWith(Sink.head)
      })).map(_=> Right( () ))
    } yield deleteResults

    //keep this in the same style as the older implementation
    resultFut.recover({
      case err:Throwable=>
        logger.error(s"Could not complete deletion of proxyId $proxyId: ${err.getMessage}", err)
        Left(err.getMessage)
    })
  }

  /**
    * Delete the give [[ProxyLocation]] record by main master ID and proxy type.
    * @param fileId main media ID for the proxy location to delete
    * @param proxyType proxy type to delete
    * @param client implicitly provided (alpakka) Dynamo client
    * @return a Future containing a DeleteItemResult
    */
  def deleteProxyRecord(fileId:String, proxyType:ProxyType.Value)(implicit client:DynamoDbAsyncClient) =
    ScanamoAlpakka(client).exec(
      table.delete("fileId"===fileId and "proxyType"===proxyType.toString)
    ).runWith(Sink.headOption)
}
