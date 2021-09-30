package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.testkit.TestProbe
import auth.BearerTokenAuth
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ObjectMetadata, Region}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTargetDAO
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType, ProxyLocation, ProxyLocationDAO, ProxyType, StorageClass}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import helpers.IndexerFactory
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.mvc.ControllerComponents
import requests.ProxyImportRequest

import scala.concurrent.ExecutionContext.Implicits.global
import java.time.ZonedDateTime
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ImportControllerSpec extends Specification with AfterAll with Mockito {
  private implicit val sys:ActorSystem = ActorSystem("ImportControllerSpec")
  private implicit val mat:Materializer = Materializer.matFromSystem

  override def afterAll() = {
    Await.ready(sys.terminate(), 30.seconds)
  }

  "Importcontroller.checkAndPerformProxyImport" should {
    "create and save a proxy record then update the item record" in {
      val config = Configuration.from(Map("externalData.indexName"->"testindex"))
      val cc = mock[ControllerComponents]
      val scanTargetDAO = mock[ScanTargetDAO]
      val proxyTargetDAO = mock[ProxyLocationDAO]
      proxyTargetDAO.saveProxy(any)(any) returns Future(None)
      val mockS3Client = mock[AmazonS3]
      mockS3Client.doesObjectExist(any,any) returns true
      mockS3Client.getRegionName returns "region-name"
      val mockedObjectMetadata = mock[ObjectMetadata]
      mockS3Client.getObjectMetadata(any,any) returns mockedObjectMetadata

      val mockedIndexer = mock[Indexer]
      val mockedIndexerFactory = mock[IndexerFactory]
      mockedIndexerFactory.get() returns mockedIndexer

      val s3ClientMgr = mock[S3ClientManager]
      s3ClientMgr.getS3Client(any,any) returns mockS3Client

      val esClientMgr = mock[ESClientManager]
      val ddbClientMgr = mock[DynamoClientManager]
      val probe = TestProbe()

      val toTest = new ImportController(config, cc, scanTargetDAO,
        proxyTargetDAO, s3ClientMgr, esClientMgr,
        mock[BearerTokenAuth], mock[SyncCacheApi], ddbClientMgr, mockedIndexerFactory, probe.ref)

      val req = ProxyImportRequest(
        "some-item-id",
        "path/to/proxy.mp3",
        Some("a-proxy-bucket"),
        ProxyType.AUDIO,
        None
      )

      val item = ArchiveEntry("some-item-id","some-bucket","path/to/media.wav",Some("region-name"), Some(".wav"),
        1234L, ZonedDateTime.now(),"", MimeType("audio","wav"), false, StorageClass.STANDARD, Seq(), mediaMetadata=None)

      mockedIndexer.getById(any)(any) returns Future(item)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-item-id"))

      val existingProxies = List()
      val result = Await.result(toTest.checkAndPerformProxyImport(req, item, "a-proxy-bucket", existingProxies), 2.seconds)

      result.header.status mustEqual 200
      there was one(mockS3Client).doesObjectExist("a-proxy-bucket","path/to/proxy.mp3")
      there was one(s3ClientMgr).getS3Client(null,Some("region-name"))
      val expectedProxyRecord = ProxyLocation("c29tZS1idWNrZXQ6cGF0aC90by9tZWRpYS53YXY=","YS1wcm94eS1idWNrZXQ6cGF0aC90by9wcm94eS5tcDM=",ProxyType.AUDIO,"a-proxy-bucket","path/to/proxy.mp3", Some("region-name"), StorageClass.STANDARD)
      there was one(proxyTargetDAO).saveProxy(org.mockito.ArgumentMatchers.eq(expectedProxyRecord))(org.mockito.ArgumentMatchers.eq(null))
      there was one(mockedIndexer).getById(org.mockito.ArgumentMatchers.eq("some-item-id"))(any)
      there was one(mockedIndexer).indexSingleItem(any,any,any)(any)
    }

    "not save a new proxy record if there is one already present and overwrite is not set" in {
      val config = Configuration.from(Map("externalData.indexName"->"testindex"))
      val cc = mock[ControllerComponents]
      val scanTargetDAO = mock[ScanTargetDAO]
      val proxyTargetDAO = mock[ProxyLocationDAO]
      proxyTargetDAO.saveProxy(any)(any) returns Future(None)
      val mockS3Client = mock[AmazonS3]
      mockS3Client.doesObjectExist(any,any) returns true
      mockS3Client.getRegionName returns "region-name"
      val mockedObjectMetadata = mock[ObjectMetadata]
      mockS3Client.getObjectMetadata(any,any) returns mockedObjectMetadata

      val mockedIndexer = mock[Indexer]
      val mockedIndexerFactory = mock[IndexerFactory]
      mockedIndexerFactory.get() returns mockedIndexer

      val s3ClientMgr = mock[S3ClientManager]
      s3ClientMgr.getS3Client(any,any) returns mockS3Client

      val esClientMgr = mock[ESClientManager]
      val ddbClientMgr = mock[DynamoClientManager]
      val probe = TestProbe()

      val toTest = new ImportController(config, cc, scanTargetDAO,
        proxyTargetDAO, s3ClientMgr, esClientMgr,
        mock[BearerTokenAuth], mock[SyncCacheApi], ddbClientMgr, mockedIndexerFactory, probe.ref)

      val req = ProxyImportRequest(
        "some-item-id",
        "path/to/proxy.mp3",
        Some("a-proxy-bucket"),
        ProxyType.AUDIO,
        None
      )

      val item = ArchiveEntry("some-item-id","some-bucket","path/to/media.wav",Some("region-name"), Some(".wav"),
        1234L, ZonedDateTime.now(),"", MimeType("audio","wav"), false, StorageClass.STANDARD, Seq(), mediaMetadata=None)

      mockedIndexer.getById(any)(any) returns Future(item)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-item-id"))

      val existingProxies = List(
        ProxyLocation("some-file-id","some-proxy-id",ProxyType.AUDIO,"a-proxy-bucket","path/to/proxy.mp3",Some("region-name"), StorageClass.STANDARD)
      )
      val result = Await.result(toTest.checkAndPerformProxyImport(req, item, "a-proxy-bucket", existingProxies), 2.seconds)

      result.header.status mustEqual 409
      there was no(mockS3Client).doesObjectExist("a-proxy-bucket","path/to/proxy.mp3")
      there was no(s3ClientMgr).getS3Client(null,Some("region-name"))
      there was no(proxyTargetDAO).saveProxy(any)(org.mockito.ArgumentMatchers.eq(null))
      there was no(mockedIndexer).getById(org.mockito.ArgumentMatchers.eq("some-item-id"))(any)
      there was no(mockedIndexer).indexSingleItem(any,any,any)(any)
    }

    "create and save a new proxy record if overwrite is allowed, even if one exists already" in {
      val config = Configuration.from(Map("externalData.indexName"->"testindex"))
      val cc = mock[ControllerComponents]
      val scanTargetDAO = mock[ScanTargetDAO]
      val proxyTargetDAO = mock[ProxyLocationDAO]
      proxyTargetDAO.saveProxy(any)(any) returns Future(None)
      val mockS3Client = mock[AmazonS3]
      mockS3Client.doesObjectExist(any,any) returns true
      mockS3Client.getRegionName returns "region-name"
      val mockedObjectMetadata = mock[ObjectMetadata]
      mockS3Client.getObjectMetadata(any,any) returns mockedObjectMetadata

      val mockedIndexer = mock[Indexer]
      val mockedIndexerFactory = mock[IndexerFactory]
      mockedIndexerFactory.get() returns mockedIndexer

      val s3ClientMgr = mock[S3ClientManager]
      s3ClientMgr.getS3Client(any,any) returns mockS3Client

      val esClientMgr = mock[ESClientManager]
      val ddbClientMgr = mock[DynamoClientManager]
      val probe = TestProbe()

      val toTest = new ImportController(config, cc, scanTargetDAO,
        proxyTargetDAO, s3ClientMgr, esClientMgr,
        mock[BearerTokenAuth], mock[SyncCacheApi], ddbClientMgr, mockedIndexerFactory, probe.ref)

      val req = ProxyImportRequest(
        "some-item-id",
        "path/to/proxy.mp3",
        Some("a-proxy-bucket"),
        ProxyType.AUDIO,
        Some(true)
      )

      val item = ArchiveEntry("some-item-id","some-bucket","path/to/media.wav",Some("region-name"), Some(".wav"),
        1234L, ZonedDateTime.now(),"", MimeType("audio","wav"), false, StorageClass.STANDARD, Seq(), mediaMetadata=None)

      mockedIndexer.getById(any)(any) returns Future(item)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-item-id"))

      val existingProxies = List(
        ProxyLocation("some-file-id","some-proxy-id",ProxyType.AUDIO,"a-proxy-bucket","path/to/proxy.mp3",Some("region-name"), StorageClass.STANDARD)
      )
      val result = Await.result(toTest.checkAndPerformProxyImport(req, item, "a-proxy-bucket", existingProxies), 2.seconds)

      result.header.status mustEqual 200
      there was one(mockS3Client).doesObjectExist("a-proxy-bucket","path/to/proxy.mp3")
      there was one(s3ClientMgr).getS3Client(null,Some("region-name"))
      val expectedProxyRecord = ProxyLocation("c29tZS1idWNrZXQ6cGF0aC90by9tZWRpYS53YXY=","YS1wcm94eS1idWNrZXQ6cGF0aC90by9wcm94eS5tcDM=",ProxyType.AUDIO,"a-proxy-bucket","path/to/proxy.mp3", Some("region-name"), StorageClass.STANDARD)
      there was one(proxyTargetDAO).saveProxy(org.mockito.ArgumentMatchers.eq(expectedProxyRecord))(org.mockito.ArgumentMatchers.eq(null))
      there was one(mockedIndexer).getById(org.mockito.ArgumentMatchers.eq("some-item-id"))(any)
      there was one(mockedIndexer).indexSingleItem(any,any,any)(any)
    }

    "return a Conflict if the requested proxy bucket is not the one for the item's media bucket" in {
      val config = Configuration.from(Map("externalData.indexName"->"testindex"))
      val cc = mock[ControllerComponents]
      val scanTargetDAO = mock[ScanTargetDAO]
      val proxyTargetDAO = mock[ProxyLocationDAO]
      proxyTargetDAO.saveProxy(any)(any) returns Future(None)
      val mockS3Client = mock[AmazonS3]
      mockS3Client.doesObjectExist(any,any) returns true
      mockS3Client.getRegionName returns "region-name"
      val mockedObjectMetadata = mock[ObjectMetadata]
      mockS3Client.getObjectMetadata(any,any) returns mockedObjectMetadata

      val mockedIndexer = mock[Indexer]
      val mockedIndexerFactory = mock[IndexerFactory]
      mockedIndexerFactory.get() returns mockedIndexer

      val s3ClientMgr = mock[S3ClientManager]
      s3ClientMgr.getS3Client(any,any) returns mockS3Client

      val esClientMgr = mock[ESClientManager]
      val ddbClientMgr = mock[DynamoClientManager]
      val probe = TestProbe()

      val toTest = new ImportController(config, cc, scanTargetDAO,
        proxyTargetDAO, s3ClientMgr, esClientMgr,
        mock[BearerTokenAuth], mock[SyncCacheApi], ddbClientMgr, mockedIndexerFactory, probe.ref)

      val req = ProxyImportRequest(
        "some-item-id",
        "path/to/proxy.mp3",
        Some("a-proxy-bucket"),
        ProxyType.AUDIO,
        None
      )

      val item = ArchiveEntry("some-item-id","some-bucket","path/to/media.wav",Some("region-name"), Some(".wav"),
        1234L, ZonedDateTime.now(),"", MimeType("audio","wav"), false, StorageClass.STANDARD, Seq(), mediaMetadata=None)

      mockedIndexer.getById(any)(any) returns Future(item)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-item-id"))

      val existingProxies = List()
      val result = Await.result(toTest.checkAndPerformProxyImport(req, item, "different-proxy-bucket", existingProxies), 2.seconds)

      result.header.status mustEqual 409
      there was no(mockS3Client).doesObjectExist("a-proxy-bucket","path/to/proxy.mp3")
      there was no(s3ClientMgr).getS3Client(null,Some("region-name"))
      there was no(proxyTargetDAO).saveProxy(any)(org.mockito.ArgumentMatchers.eq(null))
      there was no(mockedIndexer).getById(org.mockito.ArgumentMatchers.eq("some-item-id"))(any)
      there was no(mockedIndexer).indexSingleItem(any,any,any)(any)
    }

    "return Bad Request if the requested proxy does not exist" in {
      val config = Configuration.from(Map("externalData.indexName"->"testindex"))
      val cc = mock[ControllerComponents]
      val scanTargetDAO = mock[ScanTargetDAO]
      val proxyTargetDAO = mock[ProxyLocationDAO]
      proxyTargetDAO.saveProxy(any)(any) returns Future(None)
      val mockS3Client = mock[AmazonS3]
      mockS3Client.doesObjectExist(any,any) returns false
      mockS3Client.getRegionName returns "region-name"
      val mockedObjectMetadata = mock[ObjectMetadata]
      mockS3Client.getObjectMetadata(any,any) throws new RuntimeException("the file does not exist, this should not be called")

      val mockedIndexer = mock[Indexer]
      val mockedIndexerFactory = mock[IndexerFactory]
      mockedIndexerFactory.get() returns mockedIndexer

      val s3ClientMgr = mock[S3ClientManager]
      s3ClientMgr.getS3Client(any,any) returns mockS3Client

      val esClientMgr = mock[ESClientManager]
      val ddbClientMgr = mock[DynamoClientManager]
      val probe = TestProbe()

      val toTest = new ImportController(config, cc, scanTargetDAO,
        proxyTargetDAO, s3ClientMgr, esClientMgr,
        mock[BearerTokenAuth], mock[SyncCacheApi], ddbClientMgr, mockedIndexerFactory, probe.ref)

      val req = ProxyImportRequest(
        "some-item-id",
        "path/to/proxy.mp3",
        Some("a-proxy-bucket"),
        ProxyType.AUDIO,
        None
      )

      val item = ArchiveEntry("some-item-id","some-bucket","path/to/media.wav",Some("region-name"), Some(".wav"),
        1234L, ZonedDateTime.now(),"", MimeType("audio","wav"), false, StorageClass.STANDARD, Seq(), mediaMetadata=None)

      mockedIndexer.getById(any)(any) returns Future(item)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-item-id"))

      val existingProxies = List()
      val result = Await.result(toTest.checkAndPerformProxyImport(req, item, "a-proxy-bucket", existingProxies), 2.seconds)

      result.header.status mustEqual 400
      there was one(mockS3Client).doesObjectExist("a-proxy-bucket","path/to/proxy.mp3")
      there was one(s3ClientMgr).getS3Client(null,Some("region-name"))
      there was no(proxyTargetDAO).saveProxy(any)(org.mockito.ArgumentMatchers.eq(null))
      there was no(mockedIndexer).getById(any)(any)
      there was no(mockedIndexer).indexSingleItem(any,any,any)(any)
    }

  }
}
