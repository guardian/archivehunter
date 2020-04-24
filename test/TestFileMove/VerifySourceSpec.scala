package TestFileMove

import java.time.ZonedDateTime
import akka.actor.Props
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType, ProxyLocation, ProxyLocationDAO, ProxyType, StorageClass}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.FileMove.VerifySource
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.ask
import com.gu.scanamo.error.DynamoReadError
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ItemNotFound, SystemError}
import services.FileMove.GenericMoveActor._

class VerifySourceSpec extends Specification with Mockito {
  sequential
  implicit val timeout:akka.util.Timeout = 5 seconds

  "VerifySource!PerformStep" should {
    "look up the source file ID and its proxies and populate this into the transient data record" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]
      val mockedEntry = ArchiveEntry(
        "some-entry-id",
        "source-bucket",
        "path/to/source_media.mxf",
        None,
        Some("mxf"),
        1234L,
        ZonedDateTime.now(),
        "some-etag",
        MimeType("application","x-mxf"),
        proxied=true,
        StorageClass.STANDARD_IA,
        Seq(),
        mediaMetadata=None,
        beenDeleted=false
      )

      val mockedProxyData = List(
        ProxyLocation(
          "file-id-thumbnail",
          "proxy-id-thumbnail",
          ProxyType.THUMBNAIL,
          "some-proxy-bucket",
          "path/to/source_media_thumb.jpg",
          None,
          StorageClass.STANDARD
        ),
        ProxyLocation(
          "file-id-proxy",
          "proxy-id-proxy",
          ProxyType.VIDEO,
          "some-proxy-bucket",
          "path/to/source_media_proxy.mp4",
          None,
          StorageClass.STANDARD
        )
      )

      val mockedIndexer = mock[Indexer]
      mockedIndexer.getByIdFull(any)(any) returns Future(Right(mockedEntry))

      val mockedProxyDAO = mock[ProxyLocationDAO]
      mockedProxyDAO.getAllProxiesFor(any)(any) returns Future(mockedProxyData.map(entry=>Right(entry)))

      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? PerformStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepSucceeded]
      val s = result.asInstanceOf[StepSucceeded]
      s.updatedData.sourceFileProxies mustEqual Some(mockedProxyData)
      s.updatedData.entry must beSome(mockedEntry)
      there was one(mockedIndexer).getByIdFull("source-entry-id")
      there was one(mockedProxyDAO).getAllProxiesFor("source-entry-id")
    }

    "return StepFailed if the item could not be found" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]


      val mockedIndexer = mock[Indexer]
      mockedIndexer.getByIdFull(any)(any) returns Future(Left(ItemNotFound("some-entry-id")))

      val mockedProxyDAO = mock[ProxyLocationDAO]
      mockedProxyDAO.getAllProxiesFor(any)(any) returns Future.failed(new RuntimeException("should not get here"))

      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? PerformStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepFailed]
      val s = result.asInstanceOf[StepFailed]

      s.updatedData mustEqual initialData
      s.err mustEqual "Requested file id source-entry-id does not exist"
      there was one(mockedIndexer).getByIdFull("source-entry-id")
      there was no(mockedProxyDAO).getAllProxiesFor("source-entry-id")
    }

    "return StepFailed if any other error occurs" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]


      val mockedIndexer = mock[Indexer]
      mockedIndexer.getByIdFull(any)(any) returns Future(Left(SystemError("some-entry-id",new RuntimeException("kersplat!!"))))

      val mockedProxyDAO = mock[ProxyLocationDAO]
      mockedProxyDAO.getAllProxiesFor(any)(any) returns Future.failed(new RuntimeException("should not get here"))

      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? PerformStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepFailed]
      val s = result.asInstanceOf[StepFailed]

      s.updatedData mustEqual initialData
      s.err.contains("kersplat!!") must beTrue
      there was one(mockedIndexer).getByIdFull("source-entry-id")
      there was no(mockedProxyDAO).getAllProxiesFor("source-entry-id")
    }

    "return StepFailed if any of the proxy lookups fail" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]
      val mockedEntry = ArchiveEntry(
        "some-entry-id",
        "source-bucket",
        "path/to/source_media.mxf",
        None,
        Some("mxf"),
        1234L,
        ZonedDateTime.now(),
        "some-etag",
        MimeType("application","x-mxf"),
        proxied=true,
        StorageClass.STANDARD_IA,
        Seq(),
        mediaMetadata=None,
        beenDeleted=false
      )

      val mockedProxyData = List(
        ProxyLocation(
          "file-id-thumbnail",
          "proxy-id-thumbnail",
          ProxyType.THUMBNAIL,
          "some-proxy-bucket",
          "path/to/source_media_thumb.jpg",
          None,
          StorageClass.STANDARD
        )
      )
      val mockedError = mock[DynamoReadError]
      mockedError.toString returns "My hovercraft is full of eels"
      val mockedIndexer = mock[Indexer]
      mockedIndexer.getByIdFull(any)(any) returns Future(Right(mockedEntry))

      val mockedProxyDAO = mock[ProxyLocationDAO]
      mockedProxyDAO.getAllProxiesFor(any)(any) returns Future(mockedProxyData.map(entry=>Right(entry)) :+ Left(mockedError))

      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? PerformStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepFailed]
      val s = result.asInstanceOf[StepFailed]
      s.err.contains("My hovercraft is full of eels") must beTrue

      there was one(mockedIndexer).getByIdFull("source-entry-id")
      there was one(mockedProxyDAO).getAllProxiesFor("source-entry-id")
    }

    "return StepFailed if the main lookup crashes" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]


      val mockedIndexer = mock[Indexer]
      mockedIndexer.getByIdFull(any)(any) returns Future.failed(new RuntimeException("Aiiiiiiee!"))

      val mockedProxyDAO = mock[ProxyLocationDAO]
      mockedProxyDAO.getAllProxiesFor(any)(any) returns Future.failed(new RuntimeException("should not get here"))

      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? PerformStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepFailed]
      val s = result.asInstanceOf[StepFailed]

      s.updatedData mustEqual initialData
      s.err.contains("Aiiiiiiee!") must beTrue
      there was one(mockedIndexer).getByIdFull("source-entry-id")
      there was no(mockedProxyDAO).getAllProxiesFor("source-entry-id")
    }

    "return StepFailed if the proxy lookup crashes" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]

      val mockedEntry = ArchiveEntry(
        "some-entry-id",
        "source-bucket",
        "path/to/source_media.mxf",
        None,
        Some("mxf"),
        1234L,
        ZonedDateTime.now(),
        "some-etag",
        MimeType("application","x-mxf"),
        proxied=true,
        StorageClass.STANDARD_IA,
        Seq(),
        mediaMetadata=None,
        beenDeleted=false
      )

      val mockedIndexer = mock[Indexer]
      mockedIndexer.getByIdFull(any)(any) returns Future(Right(mockedEntry))

      val mockedProxyDAO = mock[ProxyLocationDAO]
      mockedProxyDAO.getAllProxiesFor(any)(any) returns Future.failed(new RuntimeException("Aiiiiiiee!"))

      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? PerformStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepFailed]
      val s = result.asInstanceOf[StepFailed]

      s.updatedData mustEqual initialData
      s.err.contains("Aiiiiiiee!") must beTrue
      there was one(mockedIndexer).getByIdFull("source-entry-id")
      there was one(mockedProxyDAO).getAllProxiesFor("source-entry-id")
    }

    "reply StepFailed if the entry has the deleted flag set" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]

      val mockedEntry = ArchiveEntry(
        "some-entry-id",
        "source-bucket",
        "path/to/source_media.mxf",
        None,
        Some("mxf"),
        1234L,
        ZonedDateTime.now(),
        "some-etag",
        MimeType("application","x-mxf"),
        proxied=true,
        StorageClass.STANDARD_IA,
        Seq(),
        mediaMetadata=None,
        beenDeleted=true
      )

      val mockedIndexer = mock[Indexer]
      mockedIndexer.getByIdFull(any)(any) returns Future(Right(mockedEntry))

      val mockedProxyDAO = mock[ProxyLocationDAO]
      mockedProxyDAO.getAllProxiesFor(any)(any) returns Future(List())

      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? PerformStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepFailed]
      val s = result.asInstanceOf[StepFailed]

      s.updatedData mustEqual initialData
      s.err.contains("has been deleted in the storage")
    }
  }

  "VerifySource!RollbackStep" should {
    "always return StepSuccessful" in new AkkaTestkitSpecs2Support {
      implicit val mockedHttpClient = mock[HttpClient]
      implicit val mockedDynamoClient = mock[DynamoClient]
      val mockedIndexer = mock[Indexer]
      val mockedProxyDAO = mock[ProxyLocationDAO]
      val initialData = FileMoveTransientData.initialise("source-entry-id","dest-bucket","dest-proxy-bucket","dest-region")
      initialData.sourceFileProxies must beNone
      initialData.entry must beNone

      val actor = system.actorOf(Props(new VerifySource(mockedIndexer, mockedProxyDAO)))
      val result = Await.result((actor ? RollbackStep(initialData)).mapTo[MoveActorMessage], 5 seconds)

      result must beAnInstanceOf[StepSucceeded]

    }
  }
}
