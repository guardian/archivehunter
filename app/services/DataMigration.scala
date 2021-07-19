package services

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, ScanRequest}
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.gu.scanamo.request.ScanamoScanRequest
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, RestoreStatusEncoder}
import org.slf4j.LoggerFactory
import services.datamigration.streamcomponents.{LightboxUpdateBuilder, LightboxUpdateSink}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntryDAO
import play.api.Configuration
import akka.stream.alpakka.dynamodb.scaladsl.DynamoImplicits._
import akka.stream.alpakka.dynamodb._
import scala.collection.JavaConverters._
import com.gu.scanamo.syntax._
import scala.concurrent.ExecutionContext.Implicits.global

import javax.inject.{Inject, Singleton}

@Singleton
class DataMigration @Inject()(config:Configuration, lightboxEntryDAO: LightboxEntryDAO, dyanmoClientMgr:DynamoClientManager)
                             (implicit actorSystem:ActorSystem, mat:Materializer) extends ZonedDateTimeEncoder with ZonedTimeFormat with RestoreStatusEncoder {
  val dynamoClient = dyanmoClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  private val logger = LoggerFactory.getLogger(getClass)

  val replacement = "@guardian.co.uk$".r

  def emailUpdater(prevValue:AttributeValue):Option[AttributeValue] = {
    val prevEmail = prevValue.getS
    val newEmail = replacement.replaceAllIn(prevEmail, "@theguardian.com")
    if(newEmail==prevEmail) {
      None
    } else {
      Some(new AttributeValue().withS(newEmail))
    }
  }

  def runMigration() = {
    for {
      updateLightbox <- updateEmailAddresses(config.get[String]("lightbox.tableName"), "userEmail")
      updateBulkEntries <- updateEmailAddresses(config.get[String]("lightbox.bulkTableName"), "userEmail")
      updateUserProfiles <- updateEmailAddresses(config.get[String]("auth.userProfileTable"),"userEmail")
    } yield (updateLightbox, updateBulkEntries, updateUserProfiles)
  }

  def updateEmailAddresses(tableName:String, primaryKeyField:String) = {
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
      .via(LightboxUpdateBuilder(primaryKeyField,emailUpdater))
      .map(updatedRecord=>{
        logger.info(s"Got updated record $updatedRecord")
        updatedRecord
      })
      .toMat(LightboxUpdateSink(tableName, config, dyanmoClientMgr))(Keep.right)
      .run()
  }
}
