import java.time.ZonedDateTime

import com.sksamuel.elastic4s.http.{ElasticError, HttpClient, RequestFailure}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType, ProxyType, StorageClass}
import helpers.ProxyLocator
import org.specs2.mock.Mockito
import org.specs2.mutable._
import play.api.Logger
import services.ProxyFrameworkQueueFunctions

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ProxyLocatorSpec extends Specification with Mockito {
  "ProxyLocator.stripFileEnding" should {
    "return the base part of a filename with an extension" in {
      val result = ProxyLocator.stripFileEnding("/path/to/some/file_with.extension")
      result mustEqual "/path/to/some/file_with"
    }

    "return the whole filename, if there is no extension" in {
      val result = ProxyLocator.stripFileEnding("/path/to/some/file_without_extension")
      result mustEqual "/path/to/some/file_without_extension"
    }
  }

  "ProxyLocator.proxyTypeForExtension" should {
    "return ProxyType.VIDEO for an obvious video extension" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/videoproxy.mp4")
      result must beSome(ProxyType.VIDEO)
    }

    "return ProxyType.AUDIO for an obvious audio extension" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/audioproxy.mp3")
      result must beSome(ProxyType.AUDIO)
    }

    "return ProxyType.IMAGE for an obvious image extension" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/thumbnail.jpg")
      result must beSome(ProxyType.THUMBNAIL)
    }

    "return ProxyType.UNKNOWN for any other type" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/thing.dfsfsdf")
      result must beSome(ProxyType.UNKNOWN)
    }

    "return None for a path that does not have a type" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/something/withnoextension")
      result must beNone
    }
  }

  "ProxyLocator.setProxiedWithRetry" should {
    "retry the index operation if a version conflict error occurs" in {
      val fakeEntry = ArchiveEntry(
        "some-item-id",
        "some-bucket",
        "path/to/item",
        None,
        None,
        1234L,
        ZonedDateTime.now(),
        "some-etag",
        MimeType("video","mp4"),
        false,
        StorageClass.STANDARD,
        Seq(),
        beenDeleted = false,
        mediaMetadata = None
      )

      val initialError = RequestFailure(409,None,Map(),ElasticError("version_conflict_engine_exception","something went splat",None,None,None,Seq()))

      implicit val mockIndexer = mock[Indexer]
      mockIndexer.indexSingleItem(any,any,any)(any) returns Future(Left(initialError)) thenReturns Future(Left(initialError)) thenReturns Future(Right("someid"))
      mockIndexer.getById(any)(any) returns Future(fakeEntry)

      implicit val mockEsClient = mock[HttpClient]

      val result = Await.result(ProxyLocator.setProxiedWithRetry("some-source-id"), 30 seconds)
      result must beRight("someid")
      there were three(mockIndexer).indexSingleItem(any, any, any)(any)
      there were three(mockIndexer).getById(any)(any)
    }

    "just return the value if the operation succeeds" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val fakeEntry = ArchiveEntry(
        "some-item-id",
        "some-bucket",
        "path/to/item",
        None,
        None,
        1234L,
        ZonedDateTime.now(),
        "some-etag",
        MimeType("video","mp4"),
        false,
        StorageClass.STANDARD,
        Seq(),
        beenDeleted = false,
        mediaMetadata = None
      )

      implicit val mockIndexer = mock[Indexer]
      mockIndexer.indexSingleItem(any,any,any)(any) returns Future(Right("someid"))
      mockIndexer.getById(any)(any) returns Future(fakeEntry)
      implicit val mockEsClient = mock[HttpClient]

      val result = Await.result(ProxyLocator.setProxiedWithRetry("some-source-id"), 30 seconds)
      result must beRight("someid")
      there was one(mockIndexer).indexSingleItem(any, any, any)(any)
      there was one(mockIndexer).getById(any)(any)
    }

    "pass back an error that is not a conflict error" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val fakeEntry = ArchiveEntry(
        "some-item-id",
        "some-bucket",
        "path/to/item",
        None,
        None,
        1234L,
        ZonedDateTime.now(),
        "some-etag",
        MimeType("video","mp4"),
        false,
        StorageClass.STANDARD,
        Seq(),
        beenDeleted = false,
        mediaMetadata = None
      )

      val initialError = RequestFailure(500,None,Map(),ElasticError("some_other_error","something went splat",None,None,None,Seq()))

      implicit val mockIndexer = mock[Indexer]
      mockIndexer.indexSingleItem(any,any,any)(any) returns Future(Left(initialError))
      mockIndexer.getById(any)(any) returns Future(fakeEntry)

      implicit val mockEsClient = mock[HttpClient]

      val result = Await.result(ProxyLocator.setProxiedWithRetry("some-source-id"), 30 seconds)
      result must beLeft
      there was one(mockIndexer).indexSingleItem(any, any, any)(any)
      there was one(mockIndexer).getById(any)(any)
    }
  }
}
