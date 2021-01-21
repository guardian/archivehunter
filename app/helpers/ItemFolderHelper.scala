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

  protected def getIndexSource(indexName:String, forCollection:String) = {
    Source.fromPublisher[SearchHit](
      esClient.publisher(search(indexName) query matchQuery("bucket.keyword", forCollection) scroll "5m")
    )
  }

  /**
   * returns a list of folders for the given collection
   * @param indexName index to query
   * @param forCollection collection (bucket) name to query
   * @param maybePrefix if set, only paths that match this prefix are considered
   * @param depth depth of path to return, e.g. if this is 1 then only top-level folders are returned
   * @return a Future containing a Seq of the paths, delimited by /
   */
  def scanFolders(indexName:String, forCollection:String, maybePrefix:Option[String], depth:Int) = {
    getIndexSource(indexName, forCollection)
      .map(_.to[ArchiveEntry])
      .filter(entry=>maybePrefix match {
        case Some(prefix)=>
          entry.path.startsWith(prefix)
        case None=>
          true
      })
      .map(entry=>{
        val parts = entry.path.split("/")
        parts.slice(0, depth).mkString("/")
      })
      .toMat(Sink.seq[String])(Keep.right)
      .run()
      .map(_.distinct)
  }
}
