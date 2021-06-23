package com.theguardian.multimedia.archivehunter.common

import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.http.index.CreateIndexResponse
import com.sksamuel.elastic4s.http.search.SearchHit

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.sksamuel.elastic4s.http.{ElasticClient, ElasticError, HttpClient, RequestFailure}
import com.sksamuel.elastic4s.mappings.FieldType._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.MDC

import scala.annotation.switch

class Indexer(indexName:String) extends ZonedDateTimeEncoder with StorageClassEncoder with ArchiveEntryHitReader {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._

  /**
    * Requests that a single item be added to the index
    * @param entryId ID of the archive entry for upsert
    * @param entry [[ArchiveEntry]] object to index
    * @param client implicitly provided elastic4s HttpClient object
    * @return a Future containing either the ID of the new item or an IndexerError containing the failure
    */
  def indexSingleItem(entry:ArchiveEntry, entryId: Option[String]=None, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)
                     (implicit client:ElasticClient):Future[Either[IndexerError,String]] = {
    val idToUse = entryId match {
      case None => entry.id
      case Some(userSpecifiedId) => userSpecifiedId
    }

    client.execute {
      update(idToUse).in(s"$indexName/entry").docAsUpsert(entry)
    }.map(response=>{
      (response.status: @switch) match {
        case 200|201|204=>Right(response.result.id)
        case 409=>Left(ConflictError(idToUse,response.error.reason))
        case _=>Left(UnexpectedReturnCode(idToUse, response.status, Some(response.error.reason)))
      }
    })
      //.map(_.result.id)
  }

  /**
    * Requests that a single item be removed from the index
    * @param entryId ID of the archive entry to remove
    * @param refreshPolicy
    * @param client
    * @return a Future contianing a String with summary info.  Future will fail on error, pick this up in the usual ways.
    */
  def removeSingleItem(entryId:String, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:ElasticClient):Future[String] = {
    client.execute {
      delete(entryId).from(s"$indexName/entry")
    }.map(result=>{
      if(result.isError) {
        throw new RuntimeException(result.error.reason) //fail the future, this is handled by caller
      } else {
        result.result.toString
      }
    })
  }

  /**
    * Creates a new index, based on the name that has been provided
    * @param shardCount number of shards to create with
    * @param replicaCount number of replicas of each shard to maintain
    * @param client implicitly provided elastic4s HttpClient object
    * @return
    */
  def newIndex(shardCount:Int, replicaCount:Int)(implicit client:ElasticClient):Future[Try[CreateIndexResponse]] = client.execute {
      createIndex(indexName) shards shardCount replicas replicaCount
  }.map(result=>{
    if(result.isError) {
      Failure(new RuntimeException(result.error.reason))
    } else {
      Success(result.result)
    }
  })

  /**
    * Perform an index lookup by ID
    * @param docId document ID to find
    * @param client implicitly provided index client
    * @return a Future containing an ArchiveEntry.  The future is cancelled if anything fails - use .recover or .recoverWith to pick this up.
    */
  def getById(docId:String)(implicit client:ElasticClient):Future[ArchiveEntry] = getByIdFull(docId).map({
    case Left(err)=> throw new RuntimeException(err.toString)
    case Right(entry)=>entry
  })

  /**
    * Perform an index lookup by ID, with error handling
    * @param docId document ID to find
    * @param client implicitly provided index client
    * @return a Future containing either an ArchiveEntry or a subclass of IndexerError describing the actual error.
    */
  def getByIdFull(docId:String)(implicit client:ElasticClient):Future[Either[IndexerError,ArchiveEntry]] = client.execute {
    get(indexName, "entry", docId)
  }.map(result=>{
    (result.status: @switch) match {
      case 200 =>
        ArchiveEntryHR.read(result.result) match {
          case Failure(err) => Left(SystemError(docId, err))
          case Success(entry) => Right(entry)
        }
      case 404 =>
        Left(ItemNotFound(docId))
      case other =>
        Left(UnexpectedReturnCode(docId, other))
    }
  })

  def deleteById(docId:String)(implicit client:ElasticClient) = client.execute {
    delete(docId) from indexName / "entry"
  }
}