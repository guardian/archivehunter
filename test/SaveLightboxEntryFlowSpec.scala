import java.time.ZonedDateTime
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.{Keep, Sink}
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, LightboxEntryDAO}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType, StorageClass}
import helpers.LightboxStreamComponents.SaveLightboxEntryFlow
import models.UserProfile
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class SaveLightboxEntryFlowSpec extends Specification with Mockito {
  "SaveLightboxEntryFlow" should {
    "call out to LightboxEntryDAO to save elements" in new AkkaTestkitSpecs2Support {
      val testArchiveEntry = ArchiveEntry("test-id","fakebucket","fakepath",None,None,None,1234L,
        ZonedDateTime.now(),"fakeetag",MimeType("application","octet-stream"),false, StorageClass.STANDARD,Seq(), false,None)
      val testUserProfile = UserProfile("userEmail@company.org",false,Some("test"),Some("test"),Seq(),true,None,None,Some(12345678L),None,None,None)

      implicit val mat = Materializer.matFromSystem(system)
      implicit val ec:ExecutionContext = system.dispatcher
      implicit val lightboxEntryDAO = mock[LightboxEntryDAO]
      implicit val esClient = mock[ElasticClient]
      implicit val indexer = mock[Indexer]

      lightboxEntryDAO.put(any)(any) returns Future(mock[LightboxEntry])
      val testelem = new SaveLightboxEntryFlow("test-bulk-id",testUserProfile)

      val testStream = Source(scala.collection.immutable.Iterable(testArchiveEntry)).viaMat(testelem)(Keep.right).to(Sink.ignore)

      val result = Await.result(testStream.run(), 30 seconds)
      there was one(lightboxEntryDAO).put(any)(any)
      result mustEqual 1

    }

    "report an underlying error as a stream failure" in new AkkaTestkitSpecs2Support {
      val testArchiveEntry = ArchiveEntry("test-id","fakebucket","fakepath",None,None,None,1234L,
        ZonedDateTime.now(),"fakeetag",MimeType("application","octet-stream"),false, StorageClass.STANDARD,Seq(), false,None)
      val testUserProfile = UserProfile("userEmail@company.org",false,Some("test"),Some("test"),Seq(),true,None,None,Some(12345678L),None,None,None)

      implicit val mat = Materializer.matFromSystem
      implicit val ec:ExecutionContext = system.dispatcher
      implicit val lightboxEntryDAO = mock[LightboxEntryDAO]
      implicit val esClient = mock[ElasticClient]
      implicit val indexer = mock[Indexer]

      lightboxEntryDAO.put(any)(any) returns Future.failed(new RuntimeException("test error"))
      val testelem = new SaveLightboxEntryFlow("test-bulk-id",testUserProfile)

      val testStream = Source(scala.collection.immutable.Iterable(testArchiveEntry)).viaMat(testelem)(Keep.right).to(Sink.ignore)


      Await.result(testStream.run(), 30 seconds) must throwA[RuntimeException]
    }
  }
}
