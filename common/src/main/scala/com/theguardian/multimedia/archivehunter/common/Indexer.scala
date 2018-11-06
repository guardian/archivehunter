package com.theguardian.multimedia.archivehunter.common

import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.http.index.CreateIndexResponse
import com.sksamuel.elastic4s.http.search.SearchHit

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.sksamuel.elastic4s.http.{HttpClient, RequestFailure}
import com.sksamuel.elastic4s.mappings.FieldType._
import io.circe.generic.auto._
import io.circe.syntax._

class Indexer(indexName:String) extends ZonedDateTimeEncoder with StorageClassEncoder with ArchiveEntryHitReader {
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
    * Requests that a single item be removed from the index
    * @param entryId ID of the archive entry to remove
    * @param refreshPolicy
    * @param client
    * @return a Future contianing a String with summary info.  Future will fail on error, pick this up in the usual ways.
    */
  def removeSingleItem(entryId:String, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:HttpClient):Future[String] = {
    client.execute {
      delete(entryId).from(s"$indexName/entry")
    }.map({
      case Left(failure)=> throw new RuntimeException(failure.error.toString) //fail the future, this is handled by caller
      case Right(success) => success.result.toString
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

  /**
    * Perform an index lookup by ID
    * @param docId document ID to find
    * @param client implicitly provided index client
    * @return a Future containing an ArchiveEntry.  The future is cancelled if anything fails - use .recover or .recoverWith to pick this up.
    */
  def getById(docId:String)(implicit client:HttpClient):Future[ArchiveEntry] = client.execute {
    get(indexName, "entry", docId)
  }.map({
    case Left(failure)=>
      throw new RuntimeException(failure.toString)
    case Right(success)=>
      ArchiveEntryHR.read(success.result) match {
        case Left(err)=>throw err
        case Right(entry)=>entry
      }
  })
}