package services

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Source
import com.sksamuel.elastic4s.http.ElasticDsl.search
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveHunterConfiguration, ProxyLocation}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTarget
import helpers._
import javax.inject.{Inject, Named, Singleton}
import play.api.Logger
import services.BulkThumbnailer.{CapacityDidUpdate, CapacityOkDoThumbnails, DoThumbnails, JobDoneCapacityReset}

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success}

object BulkThumbnailer {
  case class DoThumbnails(scanTarget:ScanTarget)

  case class CapacityDidUpdate(scanTarget: ScanTarget, tableName:String)
  case class JobDoneCapacityReset(tableName:String)
  case class CapacityOkDoThumbnails(scanTarget:ScanTarget)
}

/**
  * actor wrapper that builds an Akka stream to generate thumbnails for all items in a collection that don't have one yet.
  * @param ESClientManager
  * @param hasThumbnailFilter
  * @param createProxySink
  * @param config
  * @param system
  */
@Singleton
class BulkThumbnailer @Inject() (@Named("dynamoCapacityActor") dynamoCapacityActor: ActorRef,
                                  ESClientManager: ESClientManager, hasThumbnailFilter: HasThumbnailFilter,
                                 createProxySink: CreateProxySink, config:ArchiveHunterConfiguration, system:ActorSystem)(implicit mat:Materializer)
  extends Actor{

  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.http.ElasticDsl._

  private val logger = Logger(getClass)

  val esClient = ESClientManager.getClient()
  val indexName = config.get[String]("externalData.indexName")

  val proxyTableName = config.get[String]("proxies.tableName")
  val jobHistoryTableName = config.get[String]("externalData.jobTable")
  val scanTargetTableName = config.get[String]("externalData.scanTargets")

  implicit val ec:ExecutionContext = system.dispatcher

  override def receive: Receive = {
    case DoThumbnails(tgt)=>
      dynamoCapacityActor ! DynamoCapacityActor.UpdateCapacityTable(proxyTableName,Some(100),Some(80),Seq(
        DynamoCapacityActor.UpdateCapacityIndex("proxyIdIndex",None,Some(80))
      ), self, CapacityDidUpdate(tgt, proxyTableName))

    case CapacityDidUpdate(tgt, onTable)=>
      if(onTable==proxyTableName){
        dynamoCapacityActor ! DynamoCapacityActor.UpdateCapacityTable(jobHistoryTableName, None, Some(100), Seq(
          DynamoCapacityActor.UpdateCapacityIndex("sourcesIndex", None, Some(100)),
          DynamoCapacityActor.UpdateCapacityIndex("jobStatusIndex", None, Some(100))
        ), self, CapacityDidUpdate(tgt, jobHistoryTableName))
      } else if(onTable==jobHistoryTableName){
        dynamoCapacityActor ! DynamoCapacityActor.UpdateCapacityTable(scanTargetTableName, Some(50), None, Seq(), self, CapacityDidUpdate(tgt, scanTargetTableName))
      } else if(onTable==scanTargetTableName){
        self ! CapacityOkDoThumbnails(tgt)
      }

    case CapacityOkDoThumbnails(tgt)=>
      val queries = boolQuery().withMust(Seq(
        matchQuery("bucket.keyword", tgt.bucketName),
        not(matchQuery("storageClass.keyword","GLACIER"))
      ))
      val searchHitPublisher = esClient.publisher(search(indexName) bool queries scroll "5m")
      val searchHitSource = Source.fromPublisher(searchHitPublisher)
      val archiveEntryConverter = new SearchHitToArchiveEntryFlow
      val streamCompletionPromise = Promise[Unit]()
      val eosDetect = new EOSDetect[Unit, ArchiveEntry](streamCompletionPromise, ())

      logger.info("Bulk thumbnail of ${tgt.bucketName} starting")
      searchHitSource.via(archiveEntryConverter)
        .via(hasThumbnailFilter.async)
        .log("bulk-thumbnailer")
        .via(eosDetect)
        .to(createProxySink).run()

      streamCompletionPromise.future.onComplete({
        case Success(_)=>
          logger.info(s"Bulk thumbnail of ${tgt.bucketName} completed")
          dynamoCapacityActor ! DynamoCapacityActor.UpdateCapacityTable(proxyTableName,Some(5),Some(4),Seq(
            DynamoCapacityActor.UpdateCapacityIndex("proxyIdIndex",None,Some(4))
          ), self, JobDoneCapacityReset(proxyTableName))
          dynamoCapacityActor ! DynamoCapacityActor.UpdateCapacityTable(jobHistoryTableName, None, Some(2), Seq(
            DynamoCapacityActor.UpdateCapacityIndex("sourcesIndex", None, Some(2)),
            DynamoCapacityActor.UpdateCapacityIndex("jobStatusIndex", None, Some(2))
          ), self, JobDoneCapacityReset(jobHistoryTableName))
          dynamoCapacityActor ! DynamoCapacityActor.UpdateCapacityTable(scanTargetTableName, Some(1), None, Seq(), self, JobDoneCapacityReset(scanTargetTableName))
        case Failure(err)=>
          logger.error(s"Could not perform bulk thumbnail of ${tgt.bucketName}: ", err)
      })
    case JobDoneCapacityReset(tableName)=>
      logger.info(s"Table capacity is reset for $tableName")
  }
}
