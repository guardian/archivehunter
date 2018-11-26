package services

import akka.actor.{Actor, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Source
import com.sksamuel.elastic4s.http.ElasticDsl.search
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveHunterConfiguration, ProxyLocation}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTarget
import helpers._
import javax.inject.Inject
import play.api.Logger
import services.BulkThumbnailer.DoThumbnails

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success}

object BulkThumbnailer {
  case class DoThumbnails(scanTarget:ScanTarget)
}

/**
  * actor wrapper that builds an Akka stream to generate thumbnails for all items in a collection that don't have one yet.
  * @param ESClientManager
  * @param hasThumbnailFilter
  * @param createProxySink
  * @param config
  * @param system
  */
class BulkThumbnailer @Inject() (ESClientManager: ESClientManager, hasThumbnailFilter: HasThumbnailFilter,
                                 createProxySink: CreateProxySink, config:ArchiveHunterConfiguration, system:ActorSystem)
  extends Actor{

  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.http.ElasticDsl._

  private val logger = Logger(getClass)

  val esClient = ESClientManager.getClient()
  val indexName = config.get[String]("externalData.indexName")

  implicit val mat:Materializer = ActorMaterializer.create(system)
  implicit val ec:ExecutionContext = system.dispatcher

  override def receive: Receive = {
    case DoThumbnails(tgt)=>
      val searchHitPublisher = esClient.publisher(search(indexName) matchQuery ("bucket.keyword", tgt.bucketName) scroll "1m")
      val searchHitSource = Source.fromPublisher(searchHitPublisher)
      val archiveEntryConverter = new SearchHitToArchiveEntryFlow
      val streamCompletionPromise = Promise[Unit]()
      val eosDetect = new EOSDetect[Unit, ArchiveEntry](streamCompletionPromise, ())

      logger.info("Bulk thumbnail of ${tgt.bucketName} completed")
      searchHitSource.via(archiveEntryConverter)
        .via(hasThumbnailFilter.async)
        .log("bulk-thumbnailer")
        .via(eosDetect)
        .to(createProxySink).run()

      streamCompletionPromise.future.onComplete({
        case Success(_)=>
          logger.info(s"Bulk thumbnail of ${tgt.bucketName} completed")
        case Failure(err)=>
          logger.error(s"Could not perform bulk thumbnail of ${tgt.bucketName}: ", err)
      })
  }
}
