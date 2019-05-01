import java.time.ZonedDateTime

import org.specs2.mutable.Specification
import com.sksamuel.elastic4s.embedded.LocalNode
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.index.CreateIndexResponse
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType, StorageClass, ZonedDateTimeEncoder}
import org.elasticsearch.client.ElasticsearchClient
import org.specs2.mutable._
import org.specs2.specification.BeforeAfterAll

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import java.io._
import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.google.inject.Injector
import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.mappings.FieldType.LongType
import com.sksamuel.elastic4s.mappings.MappingDefinition
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.{AuditEntryRequestBuilder, EOSDetect}
import javax.inject.Inject
import models.{ApprovalStatusEncoder, AuditEntry, AuditEntryClass, AuditEntryClassEncoder, AuditEntryDAO}
import io.circe.generic.auto._
import play.api.Logger

import scala.concurrent.duration._

class AuditEntryDAOSpec extends Specification with InjectHelper with BeforeAfterAll with AuditEntryRequestBuilder {

  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.mappings.FieldType._
  import com.sksamuel.elastic4s.mappings._

  import org.specs2.specification.core

  private val logger = Logger(getClass)

  val localNode = LocalNode("testcluster","/tmp/auditentry-dao-testdata")
  private val esClientMgr = inject[ESClientManager]
  private val esClient = esClientMgr.getClient()
  val indexName = "audit-entry"

  private implicit val actorSystem:ActorSystem = inject[ActorSystem]
  private implicit val mat:ActorMaterializer = ActorMaterializer.create(actorSystem)

  override def map(fs: =>core.Fragments) = super.map(fs).prepend(fragmentFactory.step(beforeAll).stopOnError).append(fragmentFactory.step(afterAll))

  override def beforeAll = {
    val completionPromise = Promise[Unit]()

    println("BEFOREALL START")
    val existsResult = Await.result(esClient.execute {indexExists(indexName)}, 10 seconds)
    if(existsResult.isLeft){
      logger.error(s"Could not check for test index existence: ${existsResult.left.get}")
    } else {
      if(existsResult.right.get.result.exists) Await.ready(esClient.execute {deleteIndex(indexName)}, 10 seconds)
    }

    val createResult = Await.result(esClient.execute {createIndex(indexName) mappings(
      MappingDefinition("auditentry", fields=Seq(
        BasicFieldDefinition("fileSize", "long")
      ))
    )}, 10 seconds)
    if(createResult.isLeft){
      logger.error(s"Could not create test index: ${createResult.left.get}")
    }

    val testDataSeq = Seq(
      AuditEntry("fileid-1",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5F4D9EB3-7605-4E90-9B81-598641B0F0CB")),
      AuditEntry("fileid-1",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("507397BA-C989-4135-A567-BD86B7FD6EFD")),
      AuditEntry("fileid-3",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5F4D9EB3-7605-4E90-9B81-598641B0F0CB")),
      AuditEntry("fileid-3",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Download,Some("5F4D9EB3-7605-4E90-9B81-598641B0F0CB")),
      AuditEntry("fileid-2",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5F4D9EB3-7605-4E90-9B81-598641B0F0CB")),
      AuditEntry("fileid-4",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5F4D9EB3-7605-4E90-9B81-598641B0F0CB")),
      AuditEntry("fileid-2",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5F4D9EB3-7605-4E90-9B81-598641B0F0CB")),
    )

//    val eosDetect = new EOSDetect[Unit, AuditEntry](completionPromise, () )
//
//    val sink = Sink.fromSubscriber(esClient.subscriber[AuditEntry]())
//    val flow = Source.fromIterator(()=>testDataSeq.iterator).via(eosDetect).log("setupstream").to(sink).run()
//
//    Await.ready(completionPromise.future,10 seconds)
    try {
      val futureList = testDataSeq.map(entry => esClient.execute {
        index(indexName,"auditentry") doc entry refresh(RefreshPolicy.Immediate)
      })
      val result = Await.result(Future.sequence(futureList), 10 seconds)
      val failures = result.collect({ case Left(err) => err })
      if (failures.nonEmpty) failures.foreach(err => logger.error(s"Could not set up data: $err"))

      val showdata = Await.result(esClient.execute {
        //search(indexName) query termQuery("forBulk.keyword","5F4D9EB3-7605-4E90-9B81-598641B0F0CB")
        search(indexName) query matchAllQuery()
      }, 10 seconds)
      println(s"Raw data is $showdata")

      println("BEFOREALL COMPLETED")
    } catch {
      case err:Throwable=>
        logger.error(s"Could not set up data:", err)
    }
  }

  override def afterAll = {
    localNode.close()
  }

  "AuditEntryDAO.totalSizeForBulk" should {
    "return the total size of all entries that match the given bulk ID" in {
      val dao = inject[AuditEntryDAO]
      val result = Await.result(dao.totalSizeForBulk(UUID.fromString("5F4D9EB3-7605-4E90-9B81-598641B0F0CB")), 10 seconds)

      val showdata = Await.result(esClient.execute {
        search(indexName) query termQuery("forBulk.keyword","5F4D9EB3-7605-4E90-9B81-598641B0F0CB")
        //search(indexName) query matchAllQuery()
      }, 10 seconds)
      println(s"Raw data is $showdata")

      println(s"Got result: $result")
      result must beRight
      result.right.get mustEqual Map("value"->123*5)

      }
  }
}
