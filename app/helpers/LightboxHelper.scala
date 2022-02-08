package helpers

import java.time.ZonedDateTime
import akka.actor.{ActorRefFactory, ActorSystem}
import akka.stream.{ClosedShape, Materializer}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, RunnableGraph, Sink, Source}
import com.google.inject.Injector
import com.sksamuel.elastic4s.http.{ElasticClient, ElasticError, HttpClient, RequestFailure}
import com.sksamuel.elastic4s.searches.SearchRequest
import com.sksamuel.elastic4s.searches.queries.Query
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, LightboxIndex, StorageClass}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxEntry, LightboxEntryDAO, RestoreStatus}
import helpers.LightboxStreamComponents._
import models.UserProfile
import play.api.Logger
import responses.{GenericErrorResponse, ObjectListResponse, QuotaExceededResponse}

import scala.annotation.switch
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
    lightboxEntryDAO.put(lbEntry).map(Success.apply).recover({
      case err:Throwable=>
        logger.error(s"Could not save lightbox entry for ${userProfile.userEmail}: ${err.getMessage}", err)
        Failure(err)
    })
  }

  /**
    * update the index entry for the given item to show that it has been lightboxed.
    * @param userProfile profile for the user that is doing the lightboxing.
    * @param userAvatarUrl URL for the user's avatar. this is added into the index to improve performance in the UI by preventing the need for extra lookups
    * @param indexEntry ArchiveEntry representing the item being lightboxed
    * @param bulkId If the item is being added as part of a bulk set, this should contain the ID of the bulk set, otherwise it's None
    * @param esClient implicitly provided ElasticSearch HttpClient
    * @param indexer implicitly provided Indexer instance
    * @param ec implicitly provided Execution Context
    * @return a Future, containing Success with the updated index item's ID or a Failure if something broke.
    */
  def updateIndexLightboxed(userProfile:UserProfile, userAvatarUrl:Option[String], indexEntry:ArchiveEntry, bulkId:Option[String])(implicit esClient:ElasticClient, indexer:Indexer, ec:ExecutionContext) = {
    val lbIndex = LightboxIndex(userProfile.userEmail,userAvatarUrl, ZonedDateTime.now(), bulkId)
    logger.debug(s"lbIndex is $lbIndex")
    val updatedEntry = indexEntry.copy(lightboxEntries = indexEntry.lightboxEntries ++ Seq(lbIndex))
    logger.debug(s"updateEntry is $updatedEntry")
    indexer.indexSingleItem(updatedEntry,Some(updatedEntry.id))
  }

  /**
    * internal method, return an Akka Streams Source for the given ES query
    * @param indexName ElasticSearch index to query
    * @param queryParams Elastic4s QueryDefinition describing the query to perform
    * @param esClient implicitly provided Elastic4s HttpClient
    * @param actorRefFactory implicitly provided ActorRefFactory, get this from an ActorSystem
    * @return a Source that yields SearchHit entries.  Connect this to SearchHitToArchiveEntryFlow to convert to domain objects.
    */
  def getElasticSource(indexName:String, queryParams:Query)(implicit esClient:ElasticClient, actorRefFactory:ActorRefFactory) = {
    val publisher = esClient.publisher(search(indexName) query queryParams scroll "5m")
    Source.fromPublisher(publisher)
  }

  def getElasticSource(searchDefinition: SearchRequest)(implicit esClient:ElasticClient, actorRefFactory:ActorRefFactory) = {
    val publisher = esClient.publisher(searchDefinition scroll "5m")
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
  def getTotalSizeOfSearch(indexName:String, rq:requests.SearchRequest)(implicit esClient:ElasticClient, actorRefFactory:ActorRefFactory, materializer:Materializer) = {
    logger.info(s"${boolQuery().must(rq.toSearchParams)}")
    val src = getElasticSource(indexName, boolQuery().must(rq.toSearchParams))
    val archiveEntryConverter = new SearchHitToArchiveEntryFlow

    src.via(archiveEntryConverter).map(entry=>{
      logger.info(entry.toString)
      entry.size
    }).async.toMat(Sink.reduce[Long]((acc, entry)=>acc+entry))(Keep.right).run()
  }

  /**
    * check the total size of a bulk restore before carrying it out.
    * @param indexName index name to search
    * @param userProfile user doing the restore
    * @param searchReq SearchRequest object with the parameters describing the bulk search
    * @param esClient implicitly provided ElasticSearch HttpClient
    * @param actorRefFactory implicitly provided ActorRefFactory, from ActorSystem
    * @param materializer implicitly provided ActorMaterializer
    * @param ec implicitly provided ExecutionContext
    * @return a Future, with either a QuotaExceededResponse indicating that the restore should not be allowed or a Long indicating that it should, and giving the total size in Mb of the restore.
    */
  def testBulkAddSize(indexName:String, userProfile: UserProfile, searchReq:requests.SearchRequest)(implicit esClient:ElasticClient, actorRefFactory:ActorRefFactory, materializer:Materializer, ec:ExecutionContext) = {
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
    * run the provided SearchRequest and add the result to the given LightboxBulkEntry.
    * if any elements require restore from Glacier, this will be initated
    * @param indexName index name to query
    * @param userProfile profile of the user requesting the restore
    * @param rq SearchRequest with the search terms to restore
    * @param bulk initialised LightboxBulkEntry to associate the items with
    * @param lightboxEntryDAO implicitly provided Data Access Object for lightbox entries
    * @param system implicitly provided ActorSystem
    * @param esClient implicitly provided Elastic4s HttpClient
    * @param indexer implicitly provided Indexer object
    * @param mat implicitly provided ActorMaterializer
    * @return a Future, with the LightboxBulkEntry updated to show the number of items it now has associated
    */
  def addToBulkFromSearch(indexName:String, userProfile:UserProfile, userAvatarUrl:Option[String], rq:requests.SearchRequest, bulk:LightboxBulkEntry)
                         (implicit lightboxEntryDAO: LightboxEntryDAO, system:ActorSystem, esClient:ElasticClient, indexer:Indexer, mat:Materializer, ec:ExecutionContext, injector:Injector) = {
    val archiveEntryConverter = new SearchHitToArchiveEntryFlow

    val dynamoSaveFlow = new SaveLightboxEntryFlow(bulk.id, userProfile)
    val maybeRestoreSink = injector.getInstance(classOf[InitiateRestoreSink])

    val esSaveSink = new UpdateLightboxIndexInfoSink(bulk.id, userProfile, userAvatarUrl)

    logger.info(s"Starting add of $rq to bulk lightbox ${bulk.id}" )

    val flow = RunnableGraph.fromGraph(GraphDSL.create(dynamoSaveFlow,esSaveSink, maybeRestoreSink)((_,_,_)) { implicit b=> (actualDynamoFlow, actualESSink, actualRestoreSink) =>
      import akka.stream.scaladsl.GraphDSL.Implicits._

      val src = b.add(getElasticSource(indexName, boolQuery().must(rq.toSearchParams)))
      val entryConverter = b.add(archiveEntryConverter)
      val bcast = b.add(new Broadcast[ArchiveEntry](2, eagerCancel = true))
      src ~> entryConverter ~> bcast ~> actualDynamoFlow ~> actualRestoreSink
      bcast ~> actualESSink

      ClosedShape
    })

    val resultFutures = flow.run()

    Future.sequence(Seq(resultFutures._1, resultFutures._2)).map(addedCounts=>{
      logger.info(s"addedCounts (dynamo) ${addedCounts.head}, addedCounts (ES) ${addedCounts(1)}")
      if(addedCounts.head != addedCounts(1)){
        logger.warn(s"Mismatch between dynamodb and elastic outputs - ${addedCounts.head} records saved to dynamo but ${addedCounts(1)} records saved to ES")
      }

      bulk.copy(availCount = bulk.availCount + addedCounts.head)
    })
  }

  /**
    * return an ES query definition for the bulk id - either field not existing for "loose" or matching the id
    * @param actualBulkId
    * @return
    */
  protected def lightboxQuery(actualBulkId:String) =
    if(actualBulkId=="loose"){
      boolQuery().withNot(existsQuery("lightboxEntries.memberOfBulk"))
    } else {
      matchQuery("lightboxEntries.memberOfBulk", actualBulkId)
    }


  def lightboxSearch(indexName:String, bulkId:Option[String], userEmail:String) =  {
    val queryTerms = Seq(
      Some(matchQuery("lightboxEntries.owner", userEmail)),
      bulkId.map(actualBulkId=>lightboxQuery(actualBulkId))
    ).collect({case Some(term)=>term})

    search(indexName) query {
      nestedQuery(path="lightboxEntries", query = {
        boolQuery().must(queryTerms)
      })
    }
  }

  /**
    * returns a future that will contain the number of items that aren't associated with a given bulk entry
    * @param indexName
    * @param esClient
    * @return
    */
  def getLooseCountForUser(indexName:String, userEmail:String)(implicit esClient:ElasticClient, ec:ExecutionContext):Future[Either[ElasticError, Int]] = {
    esClient.execute {
      lightboxSearch(indexName, Some("loose"), userEmail) limit(0)
    }.map(response=>{
      (response.status: @switch) match {
        case 200=>
          Right(response.result.hits.total.toInt)
        case _=>
          Left(response.error)
      }
    })
  }

  /**
    * removes the contents of the provided LightboxBulkEntry from the lightbox of the provided user
    * @param indexName index name to search
    * @param userProfile user that is being updated
    * @param bulk LightboxBulkEntry that is being removed
    * @param lightboxEntryDAO
    * @param system
    * @param esClient
    * @param indexer
    * @param mat
    * @param ec
    * @return a Future, containing an Int of the number of items removed.  If the stream errors then the future fails.
    */
  def removeBulkContents(indexName:String, userProfile:UserProfile, bulk:LightboxBulkEntry)
                        (implicit lightboxEntryDAO: LightboxEntryDAO, system:ActorSystem, esClient:ElasticClient, indexer:Indexer, mat:Materializer, ec:ExecutionContext) = {
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
