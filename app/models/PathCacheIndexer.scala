package models

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.sksamuel.elastic4s.http.search.SearchHit
import com.sksamuel.elastic4s.searches.SearchDefinition
import com.sksamuel.elastic4s.streams.RequestBuilder
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.PathCacheExtractor
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class PathCacheIndexer(indexName:String, esClientMgr:ESClientManager, batchSize:Int=200, concurrentBatches:Int=2)
                      (implicit actorSystem:ActorSystem, mat:Materializer) extends ArchiveEntryHitReader {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.circe._
  import io.circe.generic.auto._

  private val logger = LoggerFactory.getLogger(getClass)
  private val client = esClientMgr.getClient()

  /**
   * returns a sink suitable for writing to the index
   * @return
   */
  protected def getSink() = {
    implicit val builder:RequestBuilder[PathCacheEntry] = (t: PathCacheEntry) => update(t.collection + t.key) in s"$indexName/pathcache" docAsUpsert t

    Sink.fromSubscriber(
      client.subscriber[PathCacheEntry](batchSize, concurrentBatches)
    )
  }

  /**
   * returns a source suitable for reading back the index
   * @param q
   * @return
   */
  def getSource(q:SearchDefinition) = {
    Source.fromPublisher(
      client.publisher(q)
    ).map(_.to[PathCacheEntry])
  }

  /**
   * returns the overall size of the index
   * @return
   */
  def size():Future[Long] = client.execute(count(indexName)).map({
    case Left(failure)=>
      logger.error(s"Could not perform count on $indexName: ${failure.body}")
      throw new RuntimeException(s"Index error")  //this will manifest as a failed Future
    case Right(response)=>
      response.result.count
  })

  protected def getArchiveItemSource(sourceIndexName:String) = Source.fromPublisher[SearchHit](
    client.publisher(search(sourceIndexName) query matchAllQuery() scroll "1m")
  ).map(_.to[ArchiveEntry])

  def buildIndex(sourceIndexName:String) = {
    getArchiveItemSource(sourceIndexName)
      .via(PathCacheExtractor()).async
      .toMat(getSink())(Keep.right)
      .run()
  }
}
