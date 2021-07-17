package services

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.amazonaws.services.dynamodbv2.model.ScanRequest
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.gu.scanamo.request.ScanamoScanRequest
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, RestoreStatusEncoder}
import org.slf4j.LoggerFactory
import services.datamigration.streamcomponents.{LightboxUpdateBuilder, LightboxUpdateSink}
//import com.amazonaws.services.dynamodbv2.model.{QueryRequest, ScanRequest}
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

  def emailUpdater(prevEmail:String):String = {
    replacement.replaceAllIn(prevEmail, "@theguardian.com")
  }

  def one = {
    val tableName = config.get[String]("lightbox.tableName")
    val request = new ScanRequest()
      .withTableName(tableName)
////    val request = ScanamoScanRequest(config.get[String]("lightbox.tableName"))
//    val table = Table[LightboxEntry](config.get[String]("lightbox.tableName"))
//    ScanamoAlpakka.exec(dynamoClient)(table.scan())
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
      .via(LightboxUpdateBuilder[String,String]("userEmail",emailUpdater, classOf[String]))
      .toMat(LightboxUpdateSink(tableName, config, dyanmoClientMgr))(Keep.right)
      .run()

  }
}
