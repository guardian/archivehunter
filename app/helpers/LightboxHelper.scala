package helpers

import java.time.ZonedDateTime

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.stream.{ClosedShape, Materializer}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.searches.SearchDefinition
import com.sksamuel.elastic4s.searches.queries.QueryDefinition
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, LightboxIndex, StorageClass}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxEntry, LightboxEntryDAO, RestoreStatus}
import helpers.LightboxStreamComponents.{RemoveLightboxEntrySink, RemoveLightboxIndexInfoSink, SaveLightboxEntrySink, UpdateLightboxIndexInfoSink}
import models.UserProfile
import play.api.Logger
import requests.SearchRequest
import responses.{GenericErrorResponse, ObjectListResponse, QuotaExceededResponse}
import services.GlacierRestoreActor

import scala.concurrent.{ExecutionContext, Future, Promise}
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
  def saveLightboxEntry(userProfile:UserProfile, indexEntry:ArchiveEntry, bulkId:Option[String])(implicit lightboxEntryDAO: LightboxEntryDAO, ec:ExecutionContext) = {
    val expectedRestoreStatus = indexEntry.storageClass match {
      case StorageClass.GLACIER => RestoreStatus.RS_PENDING
      case _ => RestoreStatus.RS_UNNEEDED
    }

    val lbEntry = LightboxEntry(userProfile.userEmail, indexEntry.id, ZonedDateTime.now(), expectedRestoreStatus, None, None, None, None, bulkId)
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
  def updateIndexLightboxed(userProfile:UserProfile, userAvatarUrl:Option[String], indexEntry:ArchiveEntry, bulkId:Option[String])(implicit esClient:HttpClient, indexer:Indexer, ec:ExecutionContext) = {
    val lbIndex = LightboxIndex(userProfile.userEmail,userAvatarUrl, ZonedDateTime.now(), bulkId)
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

  def testBulkAddSize(indexName:String, userProfile: UserProfile, searchReq:SearchRequest)(implicit esClient:HttpClient, actorRefFactory:ActorRefFactory, materializer:Materializer, ec:ExecutionContext) = {
    logger.info(s"Checking size of $searchReq")
    LightboxHelper.getTotalSizeOfSearch(indexName,searchReq)
      .map(totalSize=>{
        val totalSizeMb = totalSize/1048576L
        logger.info(s"Total size is $totalSizeMb Mb, userQuota is ${userProfile.perRestoreQuota.getOrElse(0L)}Mb")
        if(totalSizeMb > userProfile.perRestoreQuota.getOrElse(0L)) {
          Left(QuotaExceededResponse("quota_exceeded","Your per-request quota has been exceeded",totalSizeMb, userProfile.perRestoreQuota.getOrElse(0)))
        } else {
          Right(totalSizeMb)
        }
      })
  }

  /**
    * run the provided SearchRequest and add the result to the given LightboxBulkEntry
    * @param indexName
    * @param userProfile
    * @param rq
    * @param bulk
    * @param lightboxEntryDAO
    * @param system
    * @param esClient
    * @param indexer
    * @param mat
    * @return
    */
  def addToBulkFromSearch(indexName:String, userProfile:UserProfile, rq:SearchRequest, bulk:LightboxBulkEntry)
                         (implicit lightboxEntryDAO: LightboxEntryDAO, system:ActorSystem, esClient:HttpClient, indexer:Indexer, mat:Materializer, ec:ExecutionContext) = {
    val archiveEntryConverter = new SearchHitToArchiveEntryFlow

    val dynamoSaveSink = new SaveLightboxEntrySink(bulk.id, userProfile)
    val esSaveSink = new UpdateLightboxIndexInfoSink(bulk.id, userProfile, None)

    logger.info(s"Starting add of $rq to bulk lightbox ${bulk.id}" )

    val flow = RunnableGraph.fromGraph(GraphDSL.create(dynamoSaveSink,esSaveSink)((_,_)) { implicit b=> (actualDynamoSink, actualESSink) =>
      import akka.stream.scaladsl.GraphDSL.Implicits._

      val src = b.add(getElasticSource(indexName, boolQuery().must(rq.toSearchParams)))
      val entryConverter = b.add(archiveEntryConverter)
      val bcast = b.add(new Broadcast[ArchiveEntry](2, eagerCancel = true))

      src ~> entryConverter ~> bcast ~> actualDynamoSink
      bcast ~> actualESSink

      ClosedShape
    })

    val resultFutures = flow.run()

    Future.sequence(Seq(resultFutures._1, resultFutures._2)).map(addedCounts=>{
      if(addedCounts.head != addedCounts(1)){
        logger.warn(s"Mismatch between dynamodb and elastic outputs - ${addedCounts.head} records saved to dynamo but ${addedCounts(1)} records saved to ES")
      }

      bulk.copy(availCount = bulk.availCount + addedCounts.head)
    })
  }

  def removeBulkContents(indexName:String, userProfile:UserProfile, bulk:LightboxBulkEntry)
                        (implicit lightboxEntryDAO: LightboxEntryDAO, system:ActorSystem, esClient:HttpClient, indexer:Indexer, mat:Materializer, ec:ExecutionContext) = {
    val dynamoSaveSink = new RemoveLightboxEntrySink(userProfile.userEmail)
    val esSaveSink = new RemoveLightboxIndexInfoSink(userProfile.userEmail)
    logger.info(s"bulkid is ${bulk.id}")

    val flow = RunnableGraph.fromGraph(GraphDSL.create(dynamoSaveSink, esSaveSink)((_,_)) { implicit b => (actualDynamoSink, actualESSink) =>
      import akka.stream.scaladsl.GraphDSL.Implicits._

      val queryDef = nestedQuery(path="lightboxEntries", query = {
        matchQuery("lightboxEntries.memberOfBulk", bulk.id)
      })

      val src = b.add(getElasticSource(indexName, queryDef))
      val entryConverter = b.add(new SearchHitToArchiveEntryFlow)
      val bcast = b.add(new Broadcast[ArchiveEntry](2, eagerCancel = false))

      src ~> entryConverter ~> bcast ~> actualESSink
      bcast ~> actualDynamoSink
      ClosedShape
    })

    val resultFutures = flow.run()
    Future.sequence(Seq(resultFutures._1, resultFutures._2)).map(addedCounts=> {
      if (addedCounts.head != addedCounts(1)) {
        logger.warn(s"Mismatch between dynamodb and elastic outputs - ${addedCounts.head} records saved to dynamo but ${addedCounts(1)} records saved to ES")
      }
      logger.info(s"Removed ${addedCounts.head} records from dynamo, ${addedCounts(1)} from ES")
      addedCounts.head
    })

  }
}
