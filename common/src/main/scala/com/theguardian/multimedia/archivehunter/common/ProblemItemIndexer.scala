package com.theguardian.multimedia.archivehunter.common
import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.http.index.CreateIndexResponse
import com.sksamuel.elastic4s.http.search.SearchHit

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.sksamuel.elastic4s.http.{HttpClient, RequestFailure}
import com.sksamuel.elastic4s.mappings.FieldType._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import io.circe.generic.auto._
import io.circe.syntax._

import scala.annotation.switch

/**
  * I don't like this because it's not DRY, but I can't work out how to make this generic _and_ compile properly with
  * elastic4s implicits. So I'm doing it this way :-(
  * @param indexName
  */

class ProblemItemIndexer(indexName:String) extends ZonedDateTimeEncoder with StorageClassEncoder with ProblemItemHitReader with ProxyTypeEncoder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._

  /**
    * Requests that a single item be added to the index
    * @param entryId ID of the archive entry for upsert
    * @param entry [[ProblemItem]] object to index
    * @param client implicitly provided elastic4s HttpClient object
    * @return a Future containing a Try with either the ID of the new item or a RuntimeException containing the failure
    */
  def indexSingleItem(entry:ProblemItem, entryId: Option[String]=None, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:HttpClient):Future[Try[String]] = {
    val idToUse = entryId match {
      case None => entry.fileId
      case Some(userSpecifiedId)=> userSpecifiedId
    }

    client.execute {
      update(idToUse).in(s"$indexName/problem").docAsUpsert(entry)
    }.map({
      case Left(failure) => Failure(new RuntimeException(failure.error.toString))
      case Right(success) => Success(success.result.id)
    })
  }

  def indexSummaryCount(entry:ProblemItemCount, refreshPolicy:RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:HttpClient) =
    client.execute({
      indexInto(s"$indexName/summary").doc(entry)
    })

  /**
    * Requests that a single item be removed from the index
    * @param entryId ID of the archive entry to remove
    * @param refreshPolicy
    * @param client
    * @return a Future contianing a String with summary info.  Future will fail on error, pick this up in the usual ways.
    */
  def removeSingleItem(entryId:String, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:HttpClient):Future[String] = {
    client.execute {
      delete(entryId).from(s"$indexName/problem")
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
    * @return a Future containing an ProblemItem.  The future is cancelled if anything fails - use .recover or .recoverWith to pick this up.
    */
  def getById(docId:String)(implicit client:HttpClient):Future[ProblemItem] = getByIdFull(docId).map({
    case Left(err)=> throw new RuntimeException(err.toString)
    case Right(entry)=>entry
  })

  /**
    * Perform an index lookup by ID, with error handling
    * @param docId document ID to find
    * @param client implicitly provided index client
    * @return a Future containing either an ProblemItem or a subclass of IndexerError describing the actual error.
    */
  def getByIdFull(docId:String)(implicit client:HttpClient):Future[Either[IndexerError,ProblemItem]] = client.execute {
    get(indexName, "entry", docId)
  }.map({
    case Left(failure)=>Left(ESError(docId, failure))
    case Right(success)=>
      (success.status: @switch) match {
        case 200 =>
          ProblemItemHR.read(success.result) match {
            case Left(err) => Left(SystemError(docId, err))
            case Right(entry) => Right(entry)
          }
        case 404 =>
          Left(ItemNotFound(docId))
        case other =>
          Left(UnexpectedReturnCode(docId, other))
      }
  })
}