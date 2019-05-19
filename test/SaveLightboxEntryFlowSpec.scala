import java.time.ZonedDateTime

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.{Keep, Sink}
import cats.data.NonEmptyList
import com.gu.scanamo.error.{DynamoReadError, InvalidPropertiesError, PropertyReadError}
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntryDAO
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
      val testArchiveEntry = ArchiveEntry("test-id","fakebucket","fakepath",None,None,1234L,
        ZonedDateTime.now(),"fakeetag",MimeType("application","octet-stream"),false, StorageClass.STANDARD,Seq(), false,None)
      val testUserProfile = UserProfile("userEmail@company.org",false,Seq(),true,None,None,Some(12345678L),None,None,None, None)

      implicit val mat = ActorMaterializer.create(system)
      implicit val ec:ExecutionContext = system.dispatcher
      implicit val lightboxEntryDAO = mock[LightboxEntryDAO]
      implicit val esClient = mock[HttpClient]
      implicit val indexer = mock[Indexer]

      lightboxEntryDAO.put(any)(any) returns Future(None)
      val testelem = new SaveLightboxEntryFlow("test-bulk-id",testUserProfile)

      val testStream = Source(scala.collection.immutable.Iterable(testArchiveEntry)).viaMat(testelem)(Keep.right).to(Sink.ignore)

      val result = Await.result(testStream.run(), 30 seconds)
      there was one(lightboxEntryDAO).put(any)(any)
      result mustEqual 1

    }

    "report an underlying error as a stream failure" in new AkkaTestkitSpecs2Support {
      val testArchiveEntry = ArchiveEntry("test-id","fakebucket","fakepath",None,None,1234L,
        ZonedDateTime.now(),"fakeetag",MimeType("application","octet-stream"),false, StorageClass.STANDARD,Seq(), false,None)
      val testUserProfile = UserProfile("userEmail@company.org",false,Seq(),true,None,None,Some(12345678L),None,None,None, None)

      implicit val mat = ActorMaterializer.create(system)
      implicit val ec:ExecutionContext = system.dispatcher
      implicit val lightboxEntryDAO = mock[LightboxEntryDAO]
      implicit val esClient = mock[HttpClient]
      implicit val indexer = mock[Indexer]

      lightboxEntryDAO.put(any)(any) returns Future(Some(Left(new InvalidPropertiesError(NonEmptyList(PropertyReadError("test",null), List())))))
      val testelem = new SaveLightboxEntryFlow("test-bulk-id",testUserProfile)

      val testStream = Source(scala.collection.immutable.Iterable(testArchiveEntry)).viaMat(testelem)(Keep.right).to(Sink.ignore)


      Await.result(testStream.run(), 30 seconds) must throwA[RuntimeException]
    }
  }
}
