package services

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, ScanRequest}
import io.circe.generic.auto._
import com.sksamuel.elastic4s.circe._
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, Indexer, StorageClassEncoder, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, RestoreStatusEncoder}
import org.slf4j.LoggerFactory
import services.datamigration.streamcomponents.{LightboxUpdateBuilder, LightboxUpdateBuilderNonkey, LightboxUpdateSink, UpdateIndexLightboxEntry}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager}
import play.api.Configuration
import akka.stream.alpakka.dynamodb.scaladsl.DynamoImplicits._

import scala.collection.JavaConverters._
import com.sksamuel.elastic4s.bulk.BulkCompatibleRequest
import helpers.UserAvatarHelper
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.http.ElasticDsl._

import scala.concurrent.ExecutionContext.Implicits.global
import javax.inject.{Inject, Singleton}
import scala.concurrent.{Future, Promise}
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.streams.RequestBuilder

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

@Singleton
class DataMigration @Inject()(config:Configuration, dyanmoClientMgr:DynamoClientManager, esClientManager:ESClientManager, userAvatarHelper:UserAvatarHelper)
                             (implicit actorSystem:ActorSystem, mat:Materializer)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with RestoreStatusEncoder with ArchiveEntryHitReader with StorageClassEncoder {
  val dynamoClient = dyanmoClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  private val logger = LoggerFactory.getLogger(getClass)

  private final val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

  val replacement = "@guardian.co.uk$".r
  lazy val esClient = esClientManager.getClient()

  def emailUpdaterString(prev:String):Option[String] = {
    val newEmail = replacement.replaceAllIn(prev, "@theguardian.com")
    if(newEmail==prev) {
      None
    } else {
      Some(newEmail)
    }
  }
  def emailUpdater(prevValue:AttributeValue):Option[AttributeValue] =
    emailUpdaterString(prevValue.getS).map(new AttributeValue().withS(_))

  def runMigration() = {
    for {
      updateLightbox <- updateEmailAddresses(config.get[String]("lightbox.tableName"), "userEmail", Some("fileId"), true)
      updateBulkEntries <- updateEmailAddresses(config.get[String]("lightbox.bulkTableName"), "userEmail", None, false)
      updateUserProfiles <- updateEmailAddresses(config.get[String]("auth.userProfileTable"),"userEmail", None, true)
      updateIndex <- migrateIndexLightboxData
    } yield (updateLightbox, updateBulkEntries, updateUserProfiles, updateIndex)
  }

  def updateEmailAddresses(tableName:String, primaryKeyField:String, secondaryKeyField:Option[String], isPrimaryKey:Boolean) = {
    logger.info(s"Starting update of email addresses on table $tableName for PK field $primaryKeyField and secondary key $secondaryKeyField. Is PK update? $isPrimaryKey")
    val request = new ScanRequest()
      .withTableName(tableName)

    val processFuture = dynamoClient.source(request)
      //.toMap here converts the mutable map that `asScala` gives us into an immutable map
      .flatMapConcat(response=>
        Source.fromIterator(
          ()=>response.getItems
            .asScala
            .map(_.asScala.toMap)
            .toIterator
        )
      )
      .log("services.datamigration.stream")
      .map(record=>{
        logger.info(s"Got source record $record")
        record
      })
      .via(if(isPrimaryKey){
        LightboxUpdateBuilder(primaryKeyField,emailUpdater, secondaryKeyField)
      } else {
        LightboxUpdateBuilderNonkey(primaryKeyField, emailUpdater)
      })
      .map(updatedRecord=>{
        logger.info(s"Got updated record $updatedRecord")
        updatedRecord
      })
      .toMat(LightboxUpdateSink(tableName, config, dyanmoClientMgr))(Keep.right)
      .run()

    processFuture.onComplete({
      case Success(_)=>
        logger.info(s"Data migration for $tableName succeeded")
      case Failure(err)=>
        logger.error(s"Data migration for $tableName failed: ${err.getMessage}", err)
    })
    processFuture
  }

  /**
    * get the number of documents that need investigating and potentially updating
    * @return a Future that is
    */
  def getIndexMigrationEstimate =
    esClient
      .execute(getIndexQuery(false).limit(0))
      .flatMap(response=>{
        if(response.isError) {
          Future.failed(new RuntimeException(response.error.toString))
        } else {
          Future(response.result.hits.total)
        }
      })

  /**
    * Builds the query used to find items which are lightboxed in order to update them.
    *
    * See https://www.monterail.com/blog/how-to-index-objects-elasticsearch for more details on the "nested" query.
    *
    * @param scrolling if true, then set the "scroll" attribute to keep the query in-memory for streaming. If false, then
    *                  don't memorise the query for streaming
    * @return elastic4s SearchRequest
    */
  def getIndexQuery(scrolling:Boolean=true) = {
    val baseQuery = search(indexName) query nestedQuery(
      "lightboxEntries",
      existsQuery("lightboxEntries.owner")
    )

    if(scrolling) {
      baseQuery scroll 5.minutes
    } else {
      baseQuery
    }
  }

  /**
    * return a Publisher that is used as a stream source of items that need updating
    * @return the Publisher
    */
  def getIndexPublisher = esClient.publisher(getIndexQuery())

  /**
    * return a Subscriber that is used as a stream sink that converts updated items into update requests and commits them
    * @param completionPromise a Promise that holds no data. This is Completed when the subscriber completes without
    *                          error and Failed if the subscriber errors. Check the resulting future's error to see what
    *                          went wrong in this case
    * @return the Subscriber.
    */
  def getIndexSubs(completionPromise:Promise[Done]) = {
    implicit val builder = new RequestBuilder[ArchiveEntry] {
      import com.sksamuel.elastic4s.http.ElasticDsl._

      override def request(t: ArchiveEntry): BulkCompatibleRequest = updateById(indexName, "entry", t.id) doc t
    }

    esClient.subscriber[ArchiveEntry](
      100,  //items per batch write
      4,  //concurrent batches in-flight
      completionFn=()=>{
        completionPromise.success(Done)
        ()
      },   //all-done callback
      errorFn=(err:Throwable)=>{
        completionPromise.failure(err)
        ()
      }       //error callback
    )
  }

  /**
    * Perform data migration on the lightboxed items, i.e. update all the usernames to their newer equivalent
    * @return a Future that completes when the update is done
    */
  def migrateIndexLightboxData = {
    logger.info("Index data migration starting")
    getIndexMigrationEstimate
      .flatMap(hits=>{
        logger.info(s"Initial estimate is that $hits records need checking")
        if(hits>0) {
          innerPerformMigration
        } else {
          logger.info(s"No migration needed on the index, skipping this stage")
          //if there is nothing to migrate then complete immediately
          Future(Done)
        }
      })
  }

  def innerPerformMigration = {
    val completionPromise = Promise[Done]()

    Source
      .fromPublisher(getIndexPublisher)
      .log("services.datamigration.stream")
      .map(raw=>{
        logger.info(s"migrateIndexLightboxData: got raw record $raw")
        raw
      })
      .map(_.to[ArchiveEntry])
      .map(entry=>{
        logger.info(s"migrateIndexLightboxData: got entry ${entry}")
        entry
      })
      .via(UpdateIndexLightboxEntry(emailUpdaterString, userAvatarHelper))
      .toMat(Sink.fromSubscriber(getIndexSubs(completionPromise)))(Keep.right)
      .run()

    completionPromise.future
  }
}