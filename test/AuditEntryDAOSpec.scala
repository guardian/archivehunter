import java.time.ZonedDateTime

import org.specs2.mutable.Specification
import com.sksamuel.elastic4s.embedded.LocalNode
import org.specs2.specification.BeforeAfterAll
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.UUID
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.sksamuel.elastic4s.RefreshPolicy
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.{AuditEntryRequestBuilder, EOSDetect}
import models.{ApprovalStatusEncoder, AuditEntry, AuditEntryClass, AuditEntryClassEncoder, AuditEntryDAO}
import io.circe.generic.auto._
import org.specs2.mock.Mockito
import play.api.{Configuration, Logger}

import scala.concurrent.duration._

class AuditEntryDAOSpec extends Specification with Mockito with InjectHelper with BeforeAfterAll with AuditEntryRequestBuilder {
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
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

    val existsResult = Await.result(esClient.execute {indexExists(indexName)}, 10 seconds)
    if(existsResult.isLeft){
      logger.error(s"Could not check for test index existence: ${existsResult.left.get}")
    } else {
      if(existsResult.right.get.result.exists) Await.ready(esClient.execute {deleteIndex(indexName)}, 10 seconds)
    }

    val createResult = Await.result(esClient.execute {createIndex(indexName) mappings
      MappingDefinition("auditentry", fields=Seq(
        BasicFieldDefinition("fileSize", "long")
      ))
    }, 10 seconds)
    if(createResult.isLeft){
      logger.error(s"Could not create test index: ${createResult.left.get}")
    }

    val testDataSeq = Seq(
      AuditEntry("fileid-1",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("507397ba-c989-4135-a567-bd86b7fd6efd")),
      AuditEntry("fileid-3",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-3",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Download,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-2",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-4",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-2",123,"here","test1",ZonedDateTime.now(),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
    )

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
    } catch {
      case err:Throwable=>
        logger.error(s"Could not set up data:", err)
    }
  }

  override def afterAll = {
    localNode.close()
  }

  "AuditEntryDAO.totalSizeForBulk" should {
    "return the total size of all entries that match the given bulk ID and restore class" in {
      val mockedClientMgr = mock[ESClientManager]
      mockedClientMgr.getClient() returns esClient
      val dao = new AuditEntryDAO(Configuration.from(Map("externalData.auditIndexName"->indexName)), mockedClientMgr)
      val result = Await.result(dao.totalSizeForBulk(UUID.fromString("5F4D9EB3-7605-4E90-9B81-598641B0F0CB"), AuditEntryClass.Restore), 10 seconds)

      println(s"Got result: $result")
      result must beRight
      result.right.get.toInt mustEqual 123*5
    }

    "return the total size of all entries that match the given bulk ID and download class" in {
      val mockedClientMgr = mock[ESClientManager]
      mockedClientMgr.getClient() returns esClient
      val dao = new AuditEntryDAO(Configuration.from(Map("externalData.auditIndexName"->indexName)), mockedClientMgr)
      val result = Await.result(dao.totalSizeForBulk(UUID.fromString("5F4D9EB3-7605-4E90-9B81-598641B0F0CB"), AuditEntryClass.Download), 10 seconds)

      println(s"Got result: $result")
      result must beRight
      result.right.get.toInt mustEqual 123
    }
  }

  "AuditEntryDAO.retrieve" should {
    "retrieve a list of existing items" in {
      val mockedClientMgr = mock[ESClientManager]
      mockedClientMgr.getClient() returns esClient
      val dao = new AuditEntryDAO(Configuration.from(Map("externalData.auditIndexName"->indexName)), mockedClientMgr)

      val result = Await.result(dao.retrieve("test1","fileid-3"),10 seconds)
      result must beRight
      result.right.get.length mustEqual 2
    }

    "return empty list if nothing matches" in {
      val mockedClientMgr = mock[ESClientManager]
      mockedClientMgr.getClient() returns esClient
      val dao = new AuditEntryDAO(Configuration.from(Map("externalData.auditIndexName"->indexName)), mockedClientMgr)

      val result = Await.result(dao.retrieve("rebecca smith","fileid-3"),10 seconds)
      result must beRight
      result.right.get.length mustEqual 0
    }
  }

  "AuditEntryDAO.saveSingle" should {
    "add a new record into the index that can be found with retrieve" in {
      val mockedClientMgr = mock[ESClientManager]
      mockedClientMgr.getClient() returns esClient
      val dao = new AuditEntryDAO(Configuration.from(Map("externalData.auditIndexName"->indexName)), mockedClientMgr)

      val preRetrieveResult = Await.result(dao.retrieve("test2","fileid-5"), 10 seconds)
      preRetrieveResult must beRight
      preRetrieveResult.right.get.length mustEqual 0

      val result = Await.result(dao.saveSingle(AuditEntry("fileid-5",456,"region","test2",ZonedDateTime.now(),"something",AuditEntryClass.Restore,None)),10 seconds)
      result must beRight

      val postRetrieveResult = Await.result(dao.retrieve("test2","fileid-5"), 10 seconds)
      postRetrieveResult must beRight
      postRetrieveResult.right.get.length mustEqual 1
    }
  }
}
