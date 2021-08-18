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
    val request = new ScanRequest()
      .withTableName(tableName)

    dynamoClient.source(request)
      //.toMap here converts the mutable map that `asScala` gives us into an immutable map
      .flatMapConcat(response=>
        Source.fromIterator(
          ()=>response.getItems
            .asScala
            .map(_.asScala.toMap)
            .toIterator
        )
      )
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
  }

  def getIndexPublisher =
    esClient.publisher(search(indexName) query existsQuery("lightboxEntries") scroll 5.minutes)

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

  def migrateIndexLightboxData = {
    val completionPromise = Promise[Done]()

    Source
      .fromPublisher(getIndexPublisher)
      .map(_.to[ArchiveEntry])
      .map(entry=>{
        logger.debug(s"migrateIndexLightboxData: got entry ${entry}")
        entry
      })
      .via(UpdateIndexLightboxEntry(emailUpdaterString, userAvatarHelper))
      .toMat(Sink.fromSubscriber(getIndexSubs(completionPromise)))(Keep.right)
      .run()

    completionPromise.future
  }
}
