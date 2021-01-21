package helpers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.sksamuel.elastic4s.Hit
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import org.slf4j.LoggerFactory
import com.sksamuel.elastic4s.http.search.SearchHit
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader}
import io.circe.generic.auto._

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

@Singleton
class ItemFolderHelper @Inject() (esClientMgr:ESClientManager)(implicit actorSystem:ActorSystem, mat:Materializer) extends ArchiveEntryHitReader {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.circe._

  private val logger = LoggerFactory.getLogger(getClass)
  private val esClient = esClientMgr.getClient()

  implicit val ec:ExecutionContext = actorSystem.dispatcher

  /**
   * internal method that returns an Akka Source which yields ArchiveEntry instances for the given collection
   * @param indexName index to query
   * @param forCollection collection name
   * @return
   */
  protected def getIndexSource(indexName:String, forCollection:String, maybePrefix:Option[String]) = {
    val initialQuery = matchQuery("bucket.keyword", forCollection)
    val finalQuery = maybePrefix match {
      case None=>initialQuery
      case Some(prefixString)=>
        boolQuery().must(
          initialQuery,
          prefixQuery("path.keyword",prefixString)
        )
    }

    Source.fromGraph(
      Source.fromPublisher[SearchHit](
        esClient.publisher(search(indexName) query finalQuery scroll "1m")
      ).map(_.to[ArchiveEntry])
    )
  }

  /**
   * returns a list of folders for the given collection
   * @param indexName index to query
   * @param forCollection collection (bucket) name to query
   * @param maybePrefix if set, only paths that match this prefix are considered
   * @return a Future containing a Seq of the paths, delimited by /
   */
  def scanFolders(indexName:String, forCollection:String, maybePrefix:Option[String]) = {
    val prefixDepth = maybePrefix.map(_.split("/").length).getOrElse(0)

    getIndexSource(indexName, forCollection, maybePrefix)
      .filter(entry=>maybePrefix match {
        case Some(prefix)=>
          entry.path.startsWith(prefix)
        case None=>
          true
      }).async
      .map(_.path.split("/"))
      .filter(_.length>prefixDepth+1) //only include paths, not files at this level
      .map(parts=>{
        parts.slice(0, prefixDepth+1).mkString("/")
      })
      .toMat(Sink.seq[String])(Keep.right)
      .run()
      .map(_.distinct.map(_+"/")) //frontend expects each item to be terminated with a /
  }
}
