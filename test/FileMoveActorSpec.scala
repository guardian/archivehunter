import java.sql.Timestamp
import java.time.{LocalDateTime, ZonedDateTime}

import akka.actor.{ActorRef, ActorSystem, Props}
import org.specs2.mutable._
import akka.testkit._
import play.api.{Configuration, Logger}
import akka.pattern.ask
import com.theguardian.multimedia.archivehunter.common.ProxyLocationDAO
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModelDAO, ScanTarget}
import org.specs2.mock.Mockito
import services.FileMove.GenericMoveActor
import services.FileMove.GenericMoveActor.{FileMoveTransientData, PerformStep}
import services.FileMoveActor
import services.FileMoveActor.{MoveFailed, MoveFile, MoveSuccess}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import services.FileMove.GenericMoveActor._

class FileMoveActorSpec extends Specification with Mockito {
  sequential
  private val logger=Logger(getClass)
  implicit val timeout:akka.util.Timeout = 30.seconds

  "runNextActorInSequence" should {
    "run a list of actors providing that they all return successfully" in new AkkaTestkitSpecs2Support {
      val config = Configuration.empty
      val mockedProxyLocationDAO = mock[ProxyLocationDAO]
      val mockedESClientMgr = mock[ESClientManager]
      val mockedDynamoClientMgr = mock[DynamoClientManager]
      val mockedS3ClientMgr = mock[S3ClientManager]
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.putJob(any) returns Future(None)
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      val probe3 = TestProbe()

      val actorSeq = Seq(probe1.ref, probe2.ref, probe3.ref)
      val ac = system.actorOf(Props(new FileMoveActor(
        config,
        mockedProxyLocationDAO,
        mockedESClientMgr,
        mockedDynamoClientMgr,
        mockedJobModelDAO,
        mockedS3ClientMgr
      ) {
        override protected val fileMoveChain: Seq[ActorRef] = Seq(probe1.ref, probe2.ref, probe3.ref)
      }))

      val target = ScanTarget("some-bucket", true, None, 1234L, false, None, "some-proxy-bucket", "eu-west-1", None, None, None, None)

      val resultFuture = ac ? MoveFile("somesourcefileId", target, async=false)
      val initialData = FileMoveTransientData.initialise("somesourcefileId", "some-bucket", "some-proxy-bucket", "eu-west-1")

      probe1.expectMsg(5.seconds, PerformStep(initialData))
      logger.info(probe1.lastSender.toString)

      val updatedData = initialData.copy(destFileId = Some("destination-file"))
      probe1.reply(GenericMoveActor.StepSucceeded(updatedData))
      probe2.expectMsg(5.seconds, PerformStep(updatedData))
      probe2.reply(GenericMoveActor.StepSucceeded(updatedData))
      probe3.expectMsg(5.seconds, PerformStep(updatedData))
      probe3.reply(GenericMoveActor.StepSucceeded(updatedData))

      val result = Await.result(resultFuture, 90.seconds)
      result mustEqual MoveSuccess
    }

        "stop when an actor reports a failure and roll back the ones that had run before" in new AkkaTestkitSpecs2Support{
          val config = Configuration.empty
          val mockedProxyLocationDAO = mock[ProxyLocationDAO]
          val mockedESClientMgr = mock[ESClientManager]
          val mockedDynamoClientMgr = mock[DynamoClientManager]
          val mockedS3ClientMgr = mock[S3ClientManager]
          val mockedJobModelDAO = mock[JobModelDAO]
          mockedJobModelDAO.putJob(any) returns Future(None)
          val probe1 = TestProbe()
          val probe2 = TestProbe()
          val probe3 = TestProbe()

          val actorSeq = Seq(probe1.ref, probe2.ref, probe3.ref)
          val ac = system.actorOf(Props(new FileMoveActor(
            config,
            mockedProxyLocationDAO,
            mockedESClientMgr,
            mockedDynamoClientMgr,
            mockedJobModelDAO,
            mockedS3ClientMgr
          ) {
            override protected val fileMoveChain: Seq[ActorRef] = Seq(probe1.ref, probe2.ref, probe3.ref)
          }))

          val target = ScanTarget("some-bucket", true, None, 1234L, false, None, "some-proxy-bucket", "eu-west-1", None, None, None, None)

          val resultFuture = ac ? MoveFile("somesourcefileId", target, async=false)
          val initialData = FileMoveTransientData.initialise("somesourcefileId", "some-bucket", "some-proxy-bucket","eu-west-1")

          probe1.expectMsg(5.seconds, PerformStep(initialData))
          val updatedStage1 = initialData.copy(destFileId=Some("dest-file-id"))
          probe1.reply(GenericMoveActor.StepSucceeded(updatedStage1))
          probe2.expectMsg(5.seconds, PerformStep(updatedStage1))
          probe2.reply(GenericMoveActor.StepFailed(updatedStage1, "Something went splat!"))
          probe2.expectMsg(5.seconds, RollbackStep(updatedStage1))
          probe2.reply(GenericMoveActor.StepSucceeded(updatedStage1))
          probe1.expectMsg(5.seconds, RollbackStep(updatedStage1))
          probe1.reply(StepSucceeded(updatedStage1))

          probe3.expectNoMessage(5.seconds)

          val result = Await.result(resultFuture, 15.seconds)
          result mustEqual MoveFailed("Something went splat!")

        }

        "continue rollback even if a rollback fails" in new AkkaTestkitSpecs2Support{
          val config = Configuration.empty
          val mockedProxyLocationDAO = mock[ProxyLocationDAO]
          val mockedESClientMgr = mock[ESClientManager]
          val mockedDynamoClientMgr = mock[DynamoClientManager]
          val mockedS3ClientMgr = mock[S3ClientManager]
          val mockedJobModelDAO = mock[JobModelDAO]
          mockedJobModelDAO.putJob(any) returns Future(None)
          val probe1 = TestProbe()
          val probe2 = TestProbe()
          val probe3 = TestProbe()

          val actorSeq = Seq(probe1.ref, probe2.ref, probe3.ref)
          val ac = system.actorOf(Props(new FileMoveActor(
            config,
            mockedProxyLocationDAO,
            mockedESClientMgr,
            mockedDynamoClientMgr,
            mockedJobModelDAO,
            mockedS3ClientMgr
          ) {
            override protected val fileMoveChain: Seq[ActorRef] = Seq(probe1.ref, probe2.ref, probe3.ref)
          }))

          val target = ScanTarget("some-bucket", true, None, 1234L, false, None, "some-proxy-bucket", "eu-west-1", None, None, None, None)

          val resultFuture = ac ? MoveFile("somesourcefileId", target,async=false)
          val initialData = FileMoveTransientData.initialise("somesourcefileId", "some-bucket", "some-proxy-bucket","eu-west-1")

          probe1.expectMsg(5.seconds, PerformStep(initialData))
          probe1.reply(StepSucceeded(initialData))
          probe2.expectMsg(5.seconds, PerformStep(initialData))
          probe2.reply(StepFailed(initialData, "Fire the blobfish!"))
          probe2.expectMsg(5.seconds, RollbackStep(initialData))
          probe2.reply(StepFailed(initialData, "Fire the octopus!"))
          probe1.expectMsg(5.seconds,  RollbackStep(initialData))
          probe1.reply(StepSucceeded(initialData))

          probe3.expectNoMessage(5.seconds)

          val result = Await.result(resultFuture,15.seconds)
          result mustEqual MoveFailed("Fire the blobfish!")
        }
      }
}
