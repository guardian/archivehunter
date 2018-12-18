import akka.actor.Props
import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobModelDAO, LightboxEntry, LightboxEntryDAO}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.GlacierRestoreActor
import akka.pattern.ask
import akka.util.Timeout
import com.amazonaws.services.s3.model.RestoreObjectRequest
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class GlacierRestoreActorSpec extends Specification with Mockito {
  implicit val timeout:Timeout  = 30 seconds

  "GlacierRestoreActor.InitiateRestore" should {
    "create a job model instance, update a lightbox entry instance and tell S3 to initiate restore" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.getDispatcher
      val mockedConfig = mock[Configuration]
      val mockedEsClientMgr = mock[ESClientManager]
      mockedConfig.getOptional[Int]("archive.restoresExpireAfter") returns Some(3)
      val mockedS3ClientManager = mock[S3ClientManager]
      val mockedS3Client = mock[AmazonS3]
      mockedS3ClientManager.getClient(any) returns mockedS3Client
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any[JobModel]) returns Future(None)
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]
      mockedLightboxEntryDAO.put(any[LightboxEntry])(any) returns Future(None)

      val mockedEntry = mock[ArchiveEntry]
      mockedEntry.id returns "mock-entry-id"
      mockedEntry.bucket returns "testbucket"
      mockedEntry.path returns "testpath"
      val mockedLbEntry = mock[LightboxEntry]

      val toTest = system.actorOf(Props(new GlacierRestoreActor(mockedConfig, mockedEsClientMgr, mockedS3ClientManager, mockedJobModelDAO, mockedLightboxEntryDAO, system)))

      val result = Await.result(toTest ? GlacierRestoreActor.InitiateRestore(mockedEntry, mockedLbEntry, None), 30 seconds)
      result mustEqual GlacierRestoreActor.RestoreSuccess

      there was one(mockedS3Client).restoreObjectV2(new RestoreObjectRequest(mockedEntry.bucket, mockedEntry.path, 3))

      there was one(mockedJobModelDAO).putJob(any[JobModel])
      there was one(mockedLightboxEntryDAO).put(any[LightboxEntry])(any)

    }

  }
}
