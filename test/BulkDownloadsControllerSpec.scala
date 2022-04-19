import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Framing, Keep, Sink, Source}
import akka.testkit.TestProbe
import akka.util.ByteString
import auth.BearerTokenAuth
import com.sksamuel.elastic4s.streams.ScrollPublisher
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, MimeType, StorageClass, StorageClassEncoder, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxBulkEntryDAO, LightboxEntry, LightboxEntryDAO, RestoreStatusEncoder}
import controllers.BulkDownloadsController
import models.{ArchiveEntryDownloadSynopsis, ServerTokenDAO, ServerTokenEntry}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import play.api.mvc.ControllerComponents
import io.circe.generic.auto._
import play.api.cache.SyncCacheApi

import scala.concurrent.duration._
import java.time.ZonedDateTime
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.reflectiveCalls

class BulkDownloadsControllerSpec extends Specification with Mockito with ZonedDateTimeEncoder with ArchiveEntryHitReader with StorageClassEncoder {
  sequential

  /**
    * FIXME: it would be better to initialise an Application than build a new ActorSystem here, but unfortunately
    * Panda makes that very difficult without valid AWS credentials.
    * Revisit once we move to oauth2 authentication
    */
  private implicit val actorSystem:ActorSystem = ActorSystem("BulkDownloadsControllerSpec")
  private implicit val mat:Materializer = Materializer.matFromSystem
  private implicit val ec:ExecutionContext = actorSystem.dispatcher

  def fakeStreamingSource = Source.fromIterator(()=>Seq(
    ArchiveEntry(
      "abcde",
      "bucket1",
      "path/to/file",
      None,
      None,
      None,
      1234L,
      ZonedDateTime.now(),
      "tag",
      MimeType("application","data"),
      false,
      StorageClass.STANDARD,
      Seq(),
      false,
      None
    ),
    ArchiveEntry("xxyz",
      "bucket2",
      "path/to/file2",
      None,
      None,
      None,
      1234L,
      ZonedDateTime.now(),
      "tag",
      MimeType("application","data"),
      false,
      StorageClass.STANDARD,
      Seq(),
      false,
      None
    )
  ).iterator)

  "BulkDownloadsController.streamingEntriesForBulk" should {
    "yield a stream of NDJSON formatted ArchiveEntrySynopsis" in {
      val fakeInputData =fakeStreamingSource

      val fakeEntry = LightboxBulkEntry(
        "axz",
        "test bulk",
        "fred@smith.com",
        ZonedDateTime.now(),
        0,
        0,
        1
      )

      val fakeConfig = Configuration.from(Map(
        "externalData"-> Map("indexName"->"archivehunter", "awsRegion"->"ap-east-1")
      ))
      val toTest = new BulkDownloadsController(fakeConfig, mock[SyncCacheApi], mock[ServerTokenDAO], mock[LightboxBulkEntryDAO],
        mock[LightboxEntryDAO], mock[ESClientManager], mock[S3ClientManager], mock[ControllerComponents], mock[BearerTokenAuth], TestProbe().ref) {
        override protected def getSearchSource(p: ScrollPublisher): Source[ArchiveEntry, NotUsed] = fakeInputData

        def callFunc(bulkEntry:LightboxBulkEntry) =  streamingEntriesForBulk(bulkEntry)
      }

      val resultsFut = toTest.callFunc(fakeEntry)
        .via(Framing.delimiter(ByteString("\n"), 65535))
        .map(_.utf8String)
        .map(io.circe.parser.parse)
        .map(_.flatMap(_.as[ArchiveEntryDownloadSynopsis]))
        .toMat(Sink.seq)(Keep.right)
        .run()

      val results = Await.result(resultsFut, 30.seconds)

      println(results)
      val failures = results.collect({case Left(err)=>err})
      failures.length mustEqual 0

      results.length mustEqual 2
      results.head.toOption.get.path mustEqual "path/to/file"
      results.head.toOption.get.fileSize mustEqual 1234
      results.head.toOption.get.entryId mustEqual "abcde"
      results(1).toOption.get.path mustEqual "path/to/file2"
      results(1).toOption.get.fileSize mustEqual 1234
      results(1).toOption.get.entryId mustEqual "xxyz"
    }
  }

  "BulkDownloadsController.saveTokenOnly" should {
    "update the existing token in the database and create a long-lived token" in {

      val fakeConfig = Configuration.from(Map(
        "externalData"-> Map("indexName"->"archivehunter", "awsRegion"->"ap-east-1")
      ))

      val mockServerTokenDAO = mock[ServerTokenDAO]

      mockServerTokenDAO.put(any) returns Future(mock[ServerTokenEntry])

      val toTest = new BulkDownloadsController(fakeConfig, mock[SyncCacheApi], mockServerTokenDAO, mock[LightboxBulkEntryDAO],
        mock[LightboxEntryDAO], mock[ESClientManager], mock[S3ClientManager], mock[ControllerComponents], mock[BearerTokenAuth], TestProbe().ref)  {
        def callFunc(updatedToken:ServerTokenEntry, bulkEntry:LightboxBulkEntry) = saveTokenOnly(updatedToken, bulkEntry)
      }

      val startingToken = ServerTokenEntry(
        "asdffsfsd",
        ZonedDateTime.now(),
        Some("fred@smith.com"),
        Some(ZonedDateTime.now()),
        0,
        false,
        None
      )

      val bulk = LightboxBulkEntry(
        "xxxxads",
        "test bulk",
        "fred@smith.com",
        ZonedDateTime.now(),
        0,
        1,
        0
      )
      val result = Await.result(toTest.callFunc(startingToken, bulk), 10.seconds)

      there were two(mockServerTokenDAO).put(any)
      result.header.status mustEqual 200
    }
  }

  //I'd +like+ to test the endpoint but can't figure out how to do it while injecting fake data
}
