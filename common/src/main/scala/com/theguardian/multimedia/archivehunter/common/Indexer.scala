package com.theguardian.multimedia.archivehunter.common

import com.sksamuel.elastic4s.RefreshPolicy

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.sksamuel.elastic4s.http.{HttpClient, RequestFailure}
import com.sksamuel.elastic4s.mappings.FieldType._

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger


class Indexer(indexName:String) extends ArchiveEntryEncoder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._

  /**
    * Requests that a single item be added to the index
    * @param entryId ID of the archive entry for upsert
    * @param entry [[ArchiveEntry]] object to index
    * @param client implicitly provided elastic4s HttpClient object
    * @return a Future containing a Try with either the ID of the new item or a RuntimeException containing the failure
    */
  def indexSingleItem(entryId: String, entry:ArchiveEntry, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:HttpClient):Future[Try[String]] =
    client.execute {
      update(entryId).in(s"$indexName/entry").docAsUpsert(entry)
    }.map({
      case Left(failure)=>Failure(new RuntimeException(failure.error.toString))
      case Right(success)=>Success(success.result.id)
    })

  /**
    * Creates a new index, based on the name that has been provided
    * @param shards number of shards to create with
    * @param replicas number of replicas of each shard to maintain
    * @param client implicitly provided elastic4s HttpClient object
    * @return
    */
  def newIndex(shards:Int, replicas:Int)(implicit client:HttpClient):Future[Try[String]] = client.execute {
      createIndex(indexName) shards 3 replicas 2
  }.map({
    case Left(failure)=>Failure(new RuntimeException(failure.error.toString))
    case Right(success)=>Success(success.toString)
  })
}