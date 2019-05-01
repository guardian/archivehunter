package models

import java.time.ZonedDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Inlet, Materializer, SinkShape}
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.bulk.BulkCompatibleDefinition
import com.sksamuel.elastic4s.http.RequestFailure
import com.sksamuel.elastic4s.http.search.aggs.SumAggregationBuilder
import com.sksamuel.elastic4s.streams.RequestBuilder
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.AuditEntryRequestBuilder
import javax.inject.Inject
import play.api.{Configuration, Logger}
import io.circe.generic.auto._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AuditEntryDAO @Inject() (config:Configuration, esClientManager: ESClientManager)(implicit actorSystem:ActorSystem)
  extends AuditEntryRequestBuilder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  private val logger=Logger(getClass)
  val indexName = config.get[String]("externalData.auditIndexName")

  private implicit val esClient = esClientManager.getClient()

  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  /**
    * use akka streams to efficiently create a bulk of Audit documents in the index.
    * @param dataSource a Source that yields ArchiveEntry objects for each item that needs to be audited
    * @param requestor email of the person requesting the operation
    * @param entryClass whether this is a restore, download or something else
    * @param forBulk ID of the bulk operation that this pertains to
    * @param creationTimeOverride Optional parameter to specifically set the creation time. Set to None to use "now"
    * @return the constructed runnable graph.
    */
  def addBulkFlow(dataSource: Source[ArchiveEntry,NotUsed], requestor:String, entryClass:AuditEntryClass.Value, forBulk:Option[String], creationTimeOverride:Option[ZonedDateTime]) = {
    val subscriber = esClient.subscriber[AuditEntry]()
    dataSource
      .map(entry=>AuditEntry.fromArchiveEntry(entry,requestor, entryClass, forBulk, creationTimeOverride))
      .to(Sink.fromSubscriber(subscriber))
  }

  /**
    * use akka streams to efficiently create a bulk of Audit documents in the index.
    * this version returns a partial graph that is a SinkShape, i.e. it requires to be connected to a single outlet yielding ArchiveEntries
    * and it will build AuditEntries from that and push them to ES
    * @param requestor email of the person requesting the operation
    * @param entryClass whether this is a restore, download or something else
    * @param forBulk ID of the bulk operation that this pertains to
    * @param creationTimeOverride Optional parameter to specifically set the creation time. Defaults to now.
    * @return the constructed runnable graph.
    */
  def addBulkFlow(requestor:String, entryClass:AuditEntryClass.Value, forBulk:Option[String], creationTimeOverride:Option[ZonedDateTime]=None) = {
    val subscriber = esClient.subscriber[AuditEntry]()
    GraphDSL.create() { implicit builder=>
      import GraphDSL.Implicits._

      val mapperFlow = builder.add(Flow.fromFunction[ArchiveEntry,AuditEntry](entry=>AuditEntry.fromArchiveEntry(entry,requestor, entryClass, forBulk, creationTimeOverride)))
      mapperFlow ~> Sink.fromSubscriber(subscriber)

      SinkShape(mapperFlow.in)
    }
  }

  /**
    * aggregate up the total size of data used by requests from a specific bulk entry
    * @param forBulk bulk ID to count for
    * @return
    */
  def totalSizeForBulk(forBulk:UUID, auditEntryClass:AuditEntryClass.Value) = {
    println(s"totalSizeForBulk: index name is $indexName. Bulk ID is ${forBulk.toString}")
    esClient.execute {
      search(indexName) query boolQuery().withMust(
        matchQuery("forBulk.keyword", forBulk.toString),
        matchQuery("entryClass.keyword", auditEntryClass.toString)
      ) aggregations sumAgg("totalSize","fileSize")
    }.map({
      case Right(success)=>
        Right(success.result.aggregationsAsMap("totalSize").asInstanceOf[Map[String,Double]]("value"))
      case Left(err)=>
        Left(err)
    })
  }

  /**
    * save a single audit entry.  If saving multiple entries the streaming interface (addBulkFlow) is preferred
    * @param entry AuditEntry to save
    * @param refreshPolicy RefreshPolicy to use - i.e. whether to wait until the value is committed or return. See elastic4s documentation.
    * @return
    */
  def saveSingle(entry:AuditEntry, refreshPolicy:RefreshPolicy=RefreshPolicy.WAIT_UNTIL) = esClient.execute {
      indexInto(indexName,"auditentry") doc entry refresh refreshPolicy
    }

  /**
    * retrieve an audit entry for a given requestor and file ID
    * @param requestor
    * @param fileId
    * @return
    */
  def retrieve(requestor:String, fileId:String):Future[Either[RequestFailure, IndexedSeq[AuditEntry]]] = esClient.execute {
    search(indexName) query boolQuery().withMust(
      termQuery("requestedBy.keyword", requestor),
      termQuery("fileId.keyword", fileId)
    )
  }.map(_.map(_.result.to[AuditEntry]))
}
