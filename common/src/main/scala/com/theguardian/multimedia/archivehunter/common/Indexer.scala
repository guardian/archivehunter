package com.theguardian.multimedia.archivehunter.common

import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.http.index.CreateIndexResponse

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.sksamuel.elastic4s.http.{HttpClient, RequestFailure}
import com.sksamuel.elastic4s.mappings.FieldType._
import io.circe.generic.auto._
import io.circe.syntax._

class Indexer(indexName:String) extends ZonedDateTimeEncoder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._

  /**
    * Requests that a single item be added to the index
    * @param entryId ID of the archive entry for upsert
    * @param entry [[ArchiveEntry]] object to index
    * @param client implicitly provided elastic4s HttpClient object
    * @return a Future containing a Try with either the ID of the new item or a RuntimeException containing the failure
    */
  def indexSingleItem(entry:ArchiveEntry, entryId: Option[String]=None, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:HttpClient):Future[Try[String]] = {
    val idToUse = entryId match {
      case None => entry.id
      case Some(userSpecifiedId)=> userSpecifiedId
    }

    client.execute {
      update(idToUse).in(s"$indexName/entry").docAsUpsert(entry)
    }.map({
      case Left(failure) => Failure(new RuntimeException(failure.error.toString))
      case Right(success) => Success(success.result.id)
    })
  }

  /**
    * Creates a new index, based on the name that has been provided
    * @param shardCount number of shards to create with
    * @param replicaCount number of replicas of each shard to maintain
    * @param client implicitly provided elastic4s HttpClient object
    * @return
    */
  def newIndex(shardCount:Int, replicaCount:Int)(implicit client:HttpClient):Future[Try[CreateIndexResponse]] = client.execute {
      createIndex(indexName) shards shardCount replicas replicaCount
  }.map({
    case Left(failure)=>Failure(new RuntimeException(failure.error.toString))
    case Right(success)=>Success(success.result)
  })
}