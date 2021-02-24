package com.theguardian.multimedia.archivehunter.common
import akka.actor.ActorSystem
import akka.stream.Materializer
import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.http.index.CreateIndexResponse
import com.sksamuel.elastic4s.http.search.SearchHit

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient, RequestFailure}
import com.sksamuel.elastic4s.mappings.FieldType._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import io.circe.generic.auto._
import io.circe.syntax._

import scala.annotation.switch
import akka.stream.scaladsl._

/**
  * I don't like this because it's not DRY, but I can't work out how to make this generic _and_ compile properly with
  * elastic4s implicits. So I'm doing it this way :-(
  * @param indexName
  */

class ProblemItemIndexer(indexName:String) extends ZonedDateTimeEncoder with StorageClassEncoder with ProblemItemHitReader
  with ProblemItemCountHitReader with ProxyTypeEncoder with ProxyHealthEncoder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.circe._

  def sourceForCollection(collectionName:String)(implicit client:ElasticClient, mat:Materializer, system:ActorSystem) = {
    Source.fromPublisher(client.publisher(search(indexName) query termQuery("collection.keyword", collectionName) scroll "5m"))
  }

  /**
    * Requests that a single item be added to the index
    * @param entryId ID of the archive entry for upsert
    * @param entry [[ProblemItem]] object to index
    * @param client implicitly provided elastic4s HttpClient object
    * @return a Future containing a Try with either the ID of the new item or a RuntimeException containing the failure
    */
  def indexSingleItem(entry:ProblemItem, entryId: Option[String]=None, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:ElasticClient):Future[Try[String]] = {
    val idToUse = entryId match {
      case None => entry.fileId
      case Some(userSpecifiedId)=> userSpecifiedId
    }

    client.execute {
      update(idToUse).in(s"$indexName/problem").docAsUpsert(entry)
    }.map(response=>{
      (response.status: @switch) match {
        case 200=>Success(response.result.id)
        case other=>Failure(new RuntimeException(s"Elasticsearch returned a $other error: ${response.error.reason}"))
      }
    })

  }

  def indexSummaryCount(entry:ProblemItemCount, refreshPolicy:RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:ElasticClient) =
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
  def removeSingleItem(entryId:String, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:ElasticClient):Future[String] = {
    client.execute {
      delete(entryId).from(s"$indexName/problem")
    }.flatMap(response=>{
      (response.status: @switch) match {
        case 200=>
          Future(response.result.toString)
        case other=>
          Future.failed(new RuntimeException(s"Elasticsearch returned a $other error: ${response.error.reason}"))
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
  }.map(response=>{
    (response.status: @switch) match {
      case 200=>
        Success(response.result)
      case other=>
         Failure(new RuntimeException(s"Elasticsearch returned a $other error: ${response.error.reason}"))
    }
  })

  /**
    * Perform an index lookup by ID
    * @param docId document ID to find
    * @param client implicitly provided index client
    * @return a Future containing an ProblemItem.  The future is cancelled if anything fails - use .recover or .recoverWith to pick this up.
    */
  def getById(docId:String)(implicit client:ElasticClient):Future[ProblemItem] = getByIdFull(docId).map({
    case Left(err)=> throw new RuntimeException(err.toString)
    case Right(entry)=>entry
  })

  /**
    * Perform an index lookup by ID, with error handling
    * @param docId document ID to find
    * @param client implicitly provided index client
    * @return a Future containing either an ProblemItem or a subclass of IndexerError describing the actual error.
    */
  def getByIdFull(docId:String)(implicit client:ElasticClient):Future[Either[IndexerError,ProblemItem]] = client.execute {
    get(indexName, "problem", docId)
  }.map(result=>{
      (result.status: @switch) match {
        case 200 =>
          ProblemItemHR.read(result.result) match {
            case Failure(err) => Left(SystemError(docId, err))
            case Success(entry) => Right(entry)
          }
        case 404 =>
          Left(ItemNotFound(docId))
        case other =>
          Left(UnexpectedReturnCode(docId, other))
      }
  })

  def mostRecentStats(implicit client:ElasticClient) = client.execute(
    search(s"$indexName/summary") sortByFieldDesc "scanStart" limit 1
  ).map(response=>{
    if(response.isError) {
      Left(response.error)
    } else {
      Right(response.result.to[ProblemItemCount].headOption)
    }
  })

  def deleteEntry(entry:ProblemItem)(implicit client:ElasticClient) = client.execute {
    deleteByQuery(indexName, "problem", matchQuery("fileId", entry.fileId))
  }
}