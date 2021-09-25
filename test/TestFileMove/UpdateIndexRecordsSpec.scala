package TestFileMove

import java.time.ZonedDateTime
import akka.actor.Props
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.services.dynamodbv2.model.DeleteItemResult
import com.gu.scanamo.error.{DynamoReadError, InvalidPropertiesError, PropertyReadError}
import com.sksamuel.elastic4s.http.delete.DeleteResponse
import com.sksamuel.elastic4s.http.{ElasticClient, ElasticError, HttpClient, RequestFailure, Response}
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ESError, UnexpectedReturnCode}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.FileMove.GenericMoveActor.{FileMoveTransientData, MoveActorMessage, PerformStep, RollbackStep, StepFailed, StepSucceeded}
import services.FileMove.UpdateIndexRecords

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class UpdateIndexRecordsSpec extends Specification with Mockito {
  sequential
  import akka.pattern.ask
  implicit val timeout:akka.util.Timeout = 30 seconds

  "UpdateIndexRecords!PerformStep" should {
    "create a new index record for the copied file, register copied proxies and delete the original record and original proxies" in new AkkaTestkitSpecs2Support {
      implicit val esClient = mock[ElasticClient]
      implicit val ddbClient = mock[DynamoClient]

      val mockedIndexer = mock[Indexer]

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      mockedIndexer.getById(any)(any) returns Future(testItem)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-id"))
      mockedIndexer.deleteById(any)(any) returns Future(mock[Response[DeleteResponse]])

      val mockedProxyLocationDAO = mock[ProxyLocationDAO]
      mockedProxyLocationDAO.saveProxy(any)(any) returns Future(None)
      mockedProxyLocationDAO.deleteProxyRecord(any)(any) returns Future(Right(mock[DeleteItemResult]))
      val sourceProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )

      val destProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"dest-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"dest-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("fake-id",None,Some("dest-file-id"),Some(sourceProxyList),Some(destProxyList),"dest-media-bucket","dest-proxy-bucket", "dest-region")

      val actor = system.actorOf(Props(new UpdateIndexRecords(mockedIndexer,mockedProxyLocationDAO)))
      val result = Await.result(actor ? PerformStep(data), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]

      there was one(mockedIndexer).getById("fake-id")
      there was one(mockedIndexer).indexSingleItem(any,any,any)(any)
      there was one(mockedIndexer).deleteById("fake-id")
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList.head)
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(1))
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(2))
      there was one(mockedProxyLocationDAO).deleteProxyRecord(sourceProxyList.head.proxyId)
      there was one(mockedProxyLocationDAO).deleteProxyRecord(sourceProxyList(1).proxyId)
      there was one(mockedProxyLocationDAO).deleteProxyRecord(sourceProxyList(2).proxyId)

    }

    "not delete the original record if the copy fails" in new AkkaTestkitSpecs2Support {
      implicit val esClient = mock[ElasticClient]
      implicit val ddbClient = mock[DynamoClient]

      val mockedIndexer = mock[Indexer]

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      mockedIndexer.getById(any)(any) returns Future(testItem)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Left(ESError("some-item", ElasticError("kersplat","kersplat",None,None,None,Seq(),None))))
      mockedIndexer.deleteById(any)(any) returns Future(mock[Response[DeleteResponse]])

      val mockedProxyLocationDAO = mock[ProxyLocationDAO]
      mockedProxyLocationDAO.saveProxy(any)(any) returns Future(None)
      mockedProxyLocationDAO.deleteProxyRecord(any)(any) returns Future(Right(mock[DeleteItemResult]))
      val sourceProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )

      val destProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"dest-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"dest-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("fake-id",None,Some("dest-file-id"),Some(sourceProxyList),Some(destProxyList),"dest-media-bucket","dest-proxy-bucket","dest-region")

      val actor = system.actorOf(Props(new UpdateIndexRecords(mockedIndexer,mockedProxyLocationDAO)))
      val result = Await.result(actor ? PerformStep(data), 30 seconds).asInstanceOf[MoveActorMessage]

      there was one(mockedIndexer).getById("fake-id")
      there was one(mockedIndexer).indexSingleItem(any,any,any)(any)
      there was no(mockedIndexer).deleteById("fake-id")

      there was no(mockedProxyLocationDAO).saveProxy(any)(any)
      there was no(mockedProxyLocationDAO).deleteProxyRecord(any)(any)

      result must beAnInstanceOf[StepFailed]
      result.asInstanceOf[StepFailed].err must contain("kersplat")
    }

    "not delete the original record if any proxy copy fails" in new AkkaTestkitSpecs2Support {
      implicit val esClient = mock[ElasticClient]
      implicit val ddbClient = mock[DynamoClient]

      val mockedIndexer = mock[Indexer]

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      mockedIndexer.getById(any)(any) returns Future(testItem)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-id"))
      mockedIndexer.deleteById(any)(any) returns Future(mock[Response[DeleteResponse]])

      val mockedProxyLocationDAO = mock[ProxyLocationDAO]
      mockedProxyLocationDAO.saveProxy(any)(any) returns Future(None) thenReturns Future(None) thenReturns Future(Some(Left(mock[DynamoReadError])))
      mockedProxyLocationDAO.deleteProxyRecord(any)(any) returns Future(Right(mock[DeleteItemResult]))
      val sourceProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )

      val destProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"dest-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"dest-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("fake-id",None,Some("dest-file-id"),Some(sourceProxyList),Some(destProxyList),"dest-media-bucket","dest-proxy-bucket", "dest-region")

      val actor = system.actorOf(Props(new UpdateIndexRecords(mockedIndexer,mockedProxyLocationDAO)))
      val result = Await.result(actor ? PerformStep(data), 30 seconds).asInstanceOf[MoveActorMessage]

      there was one(mockedIndexer).getById("fake-id")
      there was one(mockedIndexer).indexSingleItem(any,any,any)(any)
      there was no(mockedIndexer).deleteById("fake-id")

      there was one(mockedProxyLocationDAO).saveProxy(destProxyList.head)
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(1))
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(2))
      there was no(mockedProxyLocationDAO).deleteProxyRecord(any)(any)

      result must beAnInstanceOf[StepFailed]
      result.asInstanceOf[StepFailed].err must contain("1 proxy copies failed")
    }

    "return a failure if at least one of the proxies fails to copy" in new AkkaTestkitSpecs2Support {
      implicit val esClient = mock[ElasticClient]
      implicit val ddbClient = mock[DynamoClient]

      val mockedIndexer = mock[Indexer]

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      mockedIndexer.getById(any)(any) returns Future(testItem)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("new-docid"))

      val mockedProxyLocationDAO = mock[ProxyLocationDAO]
      val pretendError = mock[DynamoReadError]
      mockedProxyLocationDAO.saveProxy(any)(any) returns Future(None) thenReturns Future(Some(Left(pretendError)))
      val sourceProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )

      val destProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"dest-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"dest-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("fake-id",None,Some("dest-file-id"),Some(sourceProxyList),Some(destProxyList),"dest-media-bucket","dest-proxy-bucket","dest-region")

      val actor = system.actorOf(Props(new UpdateIndexRecords(mockedIndexer,mockedProxyLocationDAO)))
      val result = Await.result(actor ? PerformStep(data), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepFailed]

      there was one(mockedIndexer).getById("fake-id")
      there was one(mockedIndexer).indexSingleItem(any,any,any)(any)
      there was no(mockedIndexer).deleteById("fake-id")    //even though this is deleted here it's reconstituted by the rollback

      //these are done in parallel so will all get triggered even in failure
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList.head)
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(1))
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(2))
    }
  }

  "UpdateIndexRecords!RollbackStep" should {
    "copy the destination ArchiveEntry record back to the original ID and then delete the former destination" in new AkkaTestkitSpecs2Support {
      implicit val esClient = mock[ElasticClient]
      implicit val ddbClient = mock[DynamoClient]

      val mockedIndexer = mock[Indexer]

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      mockedIndexer.getById(any)(any) returns Future(testItem)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("some-id"))

      val mockedProxyLocationDAO = mock[ProxyLocationDAO]
      mockedProxyLocationDAO.saveProxy(any)(any) returns Future(None)
      mockedProxyLocationDAO.deleteProxyRecord(any)(any) returns Future(Right(mock[DeleteItemResult]))

      val sourceProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )

      val destProxyList = Seq(
        ProxyLocation("fake-id","proxyid1",ProxyType.VIDEO,"dest-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid2",ProxyType.AUDIO,"dest-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("fake-id","proxyid3",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("fake-id",Some(testItem),Some("dest-file-id"),Some(sourceProxyList),Some(destProxyList),"dest-media-bucket","dest-proxy-bucket","dest-region")

      val actor = system.actorOf(Props(new UpdateIndexRecords(mockedIndexer,mockedProxyLocationDAO)))
      val result = Await.result(actor ? RollbackStep(data), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]

      there was one(mockedIndexer).getById("dest-file-id")
      there was one(mockedIndexer).indexSingleItem(testItem)
      there was one(mockedIndexer).deleteById("dest-file-id")
      there was one(mockedProxyLocationDAO).saveProxy(sourceProxyList.head)
      there was one(mockedProxyLocationDAO).saveProxy(sourceProxyList(1))
      there was one(mockedProxyLocationDAO).saveProxy(sourceProxyList(2))
      there was one(mockedProxyLocationDAO).deleteProxyRecord(sourceProxyList.head.proxyId)
      there was one(mockedProxyLocationDAO).deleteProxyRecord(sourceProxyList(1).proxyId)
      there was one(mockedProxyLocationDAO).deleteProxyRecord(sourceProxyList(2).proxyId)

    }
  }


}
