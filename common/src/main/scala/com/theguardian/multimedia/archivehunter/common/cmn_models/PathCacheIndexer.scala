package com.theguardian.multimedia.archivehunter.common.cmn_models

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient}
import com.sksamuel.elastic4s.http.search.SearchHit
import com.sksamuel.elastic4s.searches.SearchRequest
import com.sksamuel.elastic4s.streams.RequestBuilder
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.PathCacheExtractor
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader}
import org.slf4j.LoggerFactory

import scala.annotation.switch
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PathCacheIndexer(val indexName:String, client:ElasticClient, batchSize:Int=200, concurrentBatches:Int=2) extends ArchiveEntryHitReader {
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import io.circe.generic.auto._

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * returns a sink suitable for writing to the index
   * @return
   */
  protected def getSink()(implicit actorSystem:ActorSystem, mat:Materializer)  = {
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
  def getSource(q:SearchRequest)(implicit actorSystem:ActorSystem, mat:Materializer)  = {
    Source.fromPublisher(
      client.publisher(q)
    ).map(_.to[PathCacheEntry])
  }

  /**
   * returns the overall size of the index
   * @return
   */
  def size():Future[Long] = client.execute(count(indexName)).map(response=>{
    (response.status: @switch) match {
      case 200 => response.result.count
      case _ =>
        logger.error(s"Could not perform count on $indexName: ${response.error.reason}")
        throw new RuntimeException(s"Index error") //this will manifest as a failed Future
    }
  })

  protected def getArchiveItemSource(sourceIndexName:String)(implicit actorSystem:ActorSystem, mat:Materializer)  = Source.fromPublisher[SearchHit](
    client.publisher(search(sourceIndexName) query matchAllQuery() scroll "1m")
  ).map(_.to[ArchiveEntry])

  def buildIndex(sourceIndexName:String)(implicit actorSystem:ActorSystem, mat:Materializer) = {
    getArchiveItemSource(sourceIndexName)
      .via(PathCacheExtractor()).async
      .toMat(getSink())(Keep.right)
      .run()
  }

  def removeIndex(implicit actorSystem:ActorSystem, mat:Materializer) = {
    client.execute { deleteIndex(indexName) }
  } flatMap { response=>
    if(response.status>299 || response.status<200) {
      Future.failed(new RuntimeException(s"Delete index failed with a ${response.status} error: ${response.error.toString}"))
    } else {
      Future(response)
    }
  }
  /**
   * returns a list of the paths that match the given query list
   * @param collectionName collection to check
   * @param prefix path prefix that must match for a path to be considered
   * @param level depth of path to retrieve
   * @return
   */
  def getPaths(collectionName:String, prefix:Option[String], level:Int)(implicit actorSystem:ActorSystem, mat:Materializer) = {
    val queryParams = Seq(
      Some(matchQuery("collection.keyword", collectionName)),
      prefix.map(pfx=>prefixQuery("key.keyword", pfx)),
      Some(matchQuery("level", level))
    ).collect({case Some(q)=>q})

    getSource(search(indexName) query boolQuery().must(queryParams) sortByFieldAsc "key.keyword" scroll "5m")
      .toMat(Sink.seq)(Keep.right)
      .run()
  }
}
