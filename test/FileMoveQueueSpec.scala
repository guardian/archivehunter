import akka.actor.Props
import akka.stream.Materializer
import akka.testkit.TestProbe
import com.amazonaws.services.sqs.AmazonSQS
import com.theguardian.multimedia.archivehunter.common.clientManagers.SQSClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ScanTarget, ScanTargetDAO}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.{FileMoveMessage, FileMoveQueue, GenericSqsActor}
import akka.pattern.ask
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import services.FileMoveActor.{MoveFailed, MoveFile, MoveSuccess}
import services.GenericSqsActor.{HandleDomainMessage, ReadyForNextMessage}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class FileMoveQueueSpec extends Specification with Mockito {
  import services.FileMoveQueue._
  implicit val akkaAskTimeout:akka.util.Timeout = 2.seconds

  "FileMoveQueue ! CheckForNotificationsIfIdle" should {
    "send CheckForNotifications if the actor is in an IDLE state" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem

      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSQSClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val mockScanTargetDAO = mock[ScanTargetDAO]
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      toTest ! FileMoveQueue.InternalSetIdleState(true)
      toTest ! FileMoveQueue.CheckForNotificationsIfIdle

      fakeSelf.expectMsg(5.seconds, GenericSqsActor.CheckForNotifications)
    }

    "not send CheckForNotifications if the actor is not in an IDLE state" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem

      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))
      val mockSqsClientMgr = mock[SQSClientManager]
      val mockSQSClient = mock[AmazonSQS]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val mockScanTargetDAO = mock[ScanTargetDAO]
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      toTest ! FileMoveQueue.InternalSetIdleState(false)
      toTest ! FileMoveQueue.CheckForNotificationsIfIdle

      fakeSelf.expectNoMessage(2.seconds)
    }
  }

  "FileMoveQueue ! EnqueueMove" should {
    "send a JSON message onto the given SQS queue" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem

      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))

      val mockSQSClient = mock[AmazonSQS]
      val mockSqsClientMgr = mock[SQSClientManager]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val mockScanTargetDAO = mock[ScanTargetDAO]
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      val result = Await.result((toTest ? EnqueueMove("some-file-d","new-collection","fred")).mapTo[FileMoveResponse], 2.seconds)

      val expectedMsgBody="""{"fileId":"some-file-d","toCollection":"new-collection"}"""
      there was one(mockSQSClient).sendMessage("somequeue", expectedMsgBody)
      result mustEqual EnqueuedOk("some-file-d")
    }

    "return an error if the SQS send fails" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem

      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))

      val mockSQSClient = mock[AmazonSQS]
      mockSQSClient.sendMessage(any, any) throws new RuntimeException("test failure")
      val mockSqsClientMgr = mock[SQSClientManager]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val mockScanTargetDAO = mock[ScanTargetDAO]
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      val result = Await.result((toTest ? EnqueueMove("some-file-d","new-collection","fred")).mapTo[FileMoveResponse], 2.seconds)

      val expectedMsgBody="""{"fileId":"some-file-d","toCollection":"new-collection"}"""
      there was one(mockSQSClient).sendMessage("somequeue", expectedMsgBody)
      result mustEqual EnqueuedProblem("some-file-d", "test failure")
    }
  }

  "FileMoveQueue ! MoveSuccess" should {
    "set the actor state to IDLE amd delete the requesting message from SQS" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem

      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))

      val mockSQSClient = mock[AmazonSQS]
      val mockSqsClientMgr = mock[SQSClientManager]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val mockScanTargetDAO = mock[ScanTargetDAO]
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      toTest ! InternalSetIdleState(false)
      toTest ! MoveSuccess("some-file-id", Some("receipt"))

      fakeSelf.expectMsg(2.seconds, InternalSetIdleState(true))
      there was one(mockSQSClient).deleteMessage("somequeue", "receipt")
    }
  }

  "FileMoveQueue ! MoveFailed" should {
    "set the actor state to IDLE" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem

      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))

      val mockSQSClient = mock[AmazonSQS]
      val mockSqsClientMgr = mock[SQSClientManager]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val mockScanTargetDAO = mock[ScanTargetDAO]
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      toTest ! InternalSetIdleState(false)
      toTest ! MoveFailed("some-file-id", "kersplat", Some("receipt"))

      there was no(mockSQSClient).deleteMessage("somequeue", "receipt")

      fakeSelf.expectMsg(2.seconds, InternalSetIdleState(true))
    }
  }

  "FileMoveQueue ! HandleDomainMessage" should {
    "request a file move from the file-move actor then acknowledge the message" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem
      implicit val ec:ExecutionContext = system.dispatcher
      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))

      val mockSQSClient = mock[AmazonSQS]
      val mockSqsClientMgr = mock[SQSClientManager]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val fakeScanTarget = ScanTarget(
        "another-collection",
        true,
        None,
        1234L,
        false,
        None,
        "some-proxies",
        "eu-west-1",
        None,
        None,
        None,
        None
      )

      val mockScanTargetDAO = mock[ScanTargetDAO]
      mockScanTargetDAO.withScanTarget(org.mockito.ArgumentMatchers.anyString())(org.mockito.ArgumentMatchers.any[(ScanTarget)=>Unit]()) answers((args:Array[AnyRef])=>{
        val cb = args(1).asInstanceOf[(ScanTarget)=>Unit]
        cb(fakeScanTarget)
        Future(None)
      })
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      val fakeRequest = new ReceiveMessageRequest()
      toTest ! HandleDomainMessage(FileMoveMessage("some-file-id","another-collection"), "some-queue", "sqs-receipt")
      fakeSelf.expectMsg(2.seconds, InternalSetIdleState(false))
      fakeMoveActor.expectMsg(2.seconds, MoveFile("some-file-id", fakeScanTarget, Some("sqs-receipt"), fakeSelf.ref))
    }

    "not request a file move if the scan target is disabled" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = Materializer.matFromSystem
      implicit val ec:ExecutionContext = system.dispatcher
      val mockConfig = Configuration.from(Map("filemover.notificationsQueue"->"somequeue"))

      val mockSQSClient = mock[AmazonSQS]
      val mockSqsClientMgr = mock[SQSClientManager]
      mockSqsClientMgr.getClient(any) returns mockSQSClient

      val fakeScanTarget = ScanTarget(
        "another-collection",
        false,
        None,
        1234L,
        false,
        None,
        "some-proxies",
        "eu-west-1",
        None,
        None,
        None,
        None
      )

      val mockScanTargetDAO = mock[ScanTargetDAO]
      mockScanTargetDAO.withScanTarget(org.mockito.ArgumentMatchers.anyString())(org.mockito.ArgumentMatchers.any[(ScanTarget)=>Unit]()) answers((args:Array[AnyRef])=>{
        val cb = args(1).asInstanceOf[(ScanTarget)=>Unit]
        cb(fakeScanTarget)
        Future(None)
      })
      val fakeMoveActor = TestProbe()
      val fakeSelf = TestProbe()

      val toTest = system.actorOf(Props(new FileMoveQueue(mockConfig, mockSqsClientMgr, system, mat, mockScanTargetDAO, fakeMoveActor.ref) {
        override protected val ownRef = fakeSelf.ref
      }))

      toTest ! HandleDomainMessage(FileMoveMessage("some-file-id","another-collection"), "some-queue", "sqs-receipt")
      fakeSelf.expectMsg(2.seconds, ReadyForNextMessage)
      fakeMoveActor.expectNoMessage(2.seconds)
    }
  }
}
