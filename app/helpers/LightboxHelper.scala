package helpers

import java.time.ZonedDateTime

import akka.actor.ActorRefFactory
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.searches.SearchDefinition
import com.sksamuel.elastic4s.searches.queries.QueryDefinition
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, LightboxIndex, StorageClass}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, LightboxEntryDAO, RestoreStatus}
import models.UserProfile
import play.api.Logger
import requests.SearchRequest
import responses.{GenericErrorResponse, ObjectListResponse}
import services.GlacierRestoreActor

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object LightboxHelper {
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.http.ElasticDsl._

  protected def logger = Logger(getClass)

  /**
    * create a lightbox entry record in the dynamo table
    * @param userProfile user profile for the user that is storing the item
    * @param indexEntry archiveEntry object for the item being stored
    * @param lightboxEntryDAO implicitly provided Data Access object
    * @param ec implicitly provided execution context
    * @return a Future, containing a Try which either has the created LightboxEntry or a Failure
    */
  def saveLightboxEntry(userProfile:UserProfile, indexEntry:ArchiveEntry)(implicit lightboxEntryDAO: LightboxEntryDAO, ec:ExecutionContext) = {
    val expectedRestoreStatus = indexEntry.storageClass match {
      case StorageClass.GLACIER => RestoreStatus.RS_PENDING
      case _ => RestoreStatus.RS_UNNEEDED
    }

    val lbEntry = LightboxEntry(userProfile.userEmail, indexEntry.id, ZonedDateTime.now(), expectedRestoreStatus, None, None, None, None)
    lightboxEntryDAO.put(lbEntry).map({
      case None=>
        logger.debug(s"lightbox entry saved, no return")
        Success(lbEntry)
      case Some(Right(value))=>
        logger.debug(s"lightbox entry saved, returned $value")
        Success(lbEntry)
      case Some(Left(err))=>
        logger.error(s"Could not save lightbox entry: ${err.toString}")
        Failure(new RuntimeException(err.toString))
    })
  }

  /**
    * update the index entry for the given item to show that it has been lightboxed.
    * @param userProfile
    * @param userAvatarUrl
    * @param indexEntry
    * @param lightboxEntryDAO
    * @param esClient
    * @param indexer
    * @param ec
    * @return
    */
  def updateIndexLightboxed(userProfile:UserProfile, userAvatarUrl:Option[String], indexEntry:ArchiveEntry)(implicit esClient:HttpClient, indexer:Indexer, ec:ExecutionContext) = {
    val lbIndex = LightboxIndex(userProfile.userEmail,userAvatarUrl, ZonedDateTime.now())
    logger.debug(s"lbIndex is $lbIndex")
    val updatedEntry = indexEntry.copy(lightboxEntries = indexEntry.lightboxEntries ++ Seq(lbIndex))
    logger.debug(s"updateEntry is $updatedEntry")
    indexer.indexSingleItem(updatedEntry,Some(updatedEntry.id))
  }

  protected def getElasticSource(indexName:String, queryParams:QueryDefinition)(implicit esClient:HttpClient, actorRefFactory:ActorRefFactory) = {
    val publisher = esClient.publisher(search(indexName) query queryParams scroll "1m")
    Source.fromPublisher(publisher)
  }

  /**
    * performs a search expresssed as a SearchRequest, and returns the total size in bytes that it would process.
    * Used for checking whether a search would breach quota limits
    * @param indexName index to run the search against
    * @param rq SearchRequest instance
    * @param esClient implicitly provided elasticsearch HttpClient
    * @param actorRefFactory implicitly provided ActorRefFactory
    * @param materializer implicitly provided Materializer
    * @return a Future, containing a Long which is the number of bytes
    */
  def getTotalSizeOfSearch(indexName:String, rq:SearchRequest)(implicit esClient:HttpClient, actorRefFactory:ActorRefFactory, materializer:Materializer) = {
    logger.info(s"${boolQuery().must(rq.toSearchParams)}")
    val src = getElasticSource(indexName, boolQuery().must(rq.toSearchParams))
    val archiveEntryConverter = new SearchHitToArchiveEntryFlow

    src.via(archiveEntryConverter).map(entry=>{
      logger.info(entry.toString)
      entry.size
    }).async.toMat(Sink.reduce[Long]((acc, entry)=>acc+entry))(Keep.right).run()
  }
}
