import java.time.ZonedDateTime
import java.util.UUID

import akka.actor.Props
import akka.testkit.TestProbe
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntryDAO
import models.{ApprovalStatus, AuditBulk, AuditBulkDAO, AuditEntryDAO, UserProfile, UserProfileDAO}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.{AuditApprovalActor, GlacierRestoreActor}
import akka.pattern.ask
import akka.stream.alpakka.s3.impl.StorageClass.Glacier
import com.gu.scanamo.error.DynamoReadError
import com.sksamuel.elastic4s.http.{RequestFailure, RequestSuccess, Shards}
import com.sksamuel.elastic4s.http.update.UpdateResponse
import services.AuditApprovalActor.{AAMMsg, AdminApprovalOverride, ApprovalGranted, ApprovalPending, ApprovalRejected, AutomatedApprovalCheck}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class AuditApprovalActorSpec extends Specification with Mockito {
  sequential

  implicit val timeout:akka.util.Timeout = 10 seconds

  "AuditApprovalActor!AutomatedApprovalCheck" should {
    "automatically approve a request that is below a user's restore limit" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]

      mockedAuditEntryDAO.totalSizeForBulk(any, any) returns Future(Right(1234))
      mockedUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(UserProfile("test@test.com",false,Seq(),true,Some(12345)))))

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val result = Await.result((actor ? AutomatedApprovalCheck(testAuditBulk)).mapTo[AAMMsg], 10 seconds)
      there was one(mockedAuditBulkDAO).saveSingle(any)

      result must beAnInstanceOf[ApprovalGranted]
    }

    "automatically reject a request that is above a user's restore limit" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]

      mockedAuditEntryDAO.totalSizeForBulk(any, any) returns Future(Right(123456))
      mockedUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(UserProfile("test@test.com",false,Seq(),true,Some(12345)))))

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val result = Await.result((actor ? AutomatedApprovalCheck(testAuditBulk)).mapTo[AAMMsg], 10 seconds)
      there was one(mockedAuditBulkDAO).saveSingle(any)

      result must beAnInstanceOf[ApprovalRejected]
    }

    "require admin approval if the user has no limit" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]

      mockedAuditEntryDAO.totalSizeForBulk(any, any) returns Future(Right(123456))
      mockedUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(UserProfile("test@test.com",false,Seq(),true,None))))

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val result = Await.result((actor ? AutomatedApprovalCheck(testAuditBulk)).mapTo[AAMMsg], 10 seconds)
      there was no(mockedAuditBulkDAO).saveSingle(any)

      result must beAnInstanceOf[ApprovalPending]
    }

    "require admin approval if the size lookup fails" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]

      mockedAuditEntryDAO.totalSizeForBulk(any, any) returns Future(Left(mock[RequestFailure]))
      mockedUserProfileDAO.userProfileForEmail(any) returns Future(Some(Right(UserProfile("test@test.com",false,Seq(),true,None))))

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val result = Await.result((actor ? AutomatedApprovalCheck(testAuditBulk)).mapTo[AAMMsg], 10 seconds)
      there was no(mockedAuditBulkDAO).saveSingle(any)

      result must beAnInstanceOf[ApprovalPending]
    }

    "require admin approval if the user profile lookup fails" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]

      mockedAuditEntryDAO.totalSizeForBulk(any, any) returns Future(Right(123456))
      mockedUserProfileDAO.userProfileForEmail(any) returns Future(Some(Left(mock[DynamoReadError])))

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val result = Await.result((actor ? AutomatedApprovalCheck(testAuditBulk)).mapTo[AAMMsg], 10 seconds)
      there was no(mockedAuditBulkDAO).saveSingle(any)

      result must beAnInstanceOf[ApprovalPending]
    }
  }

  "AuditApprovalActor ! AdminApprovalOverride" should {
    "update the given auditbulk and save it, then trigger a bulk restore only if approval status is Allowed" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]
      val mockedUserProfile = mock[UserProfile]

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)
      val adminsUserProfile = UserProfile("some-admin@thecompany.org",true,Seq(),false,None)

      val result = Await.result((actor ? AdminApprovalOverride(testAuditBulk,adminsUserProfile,ApprovalStatus.Allowed,"automated testing")).mapTo[AAMMsg], 10 seconds)
      there was one(mockedAuditBulkDAO).saveSingle(any)
      mockedGlacierRestoreActor.expectMsg(GlacierRestoreActor.InitiateBulkRestore("test-lightbox-bulk",mockedUserProfile, None))
      result must beAnInstanceOf[ApprovalGranted]
    }

    "reject approval attempt if the UserProfile does not identify an admin" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)
      val adminsUserProfile = UserProfile("some-admin@thecompany.org",false,Seq(),false,None)

      val result = Await.result((actor ? AdminApprovalOverride(testAuditBulk,adminsUserProfile,ApprovalStatus.Allowed,"automated testing")).mapTo[AAMMsg], 10 seconds)
      there was no(mockedAuditBulkDAO).saveSingle(any)
      mockedGlacierRestoreActor.expectNoMessage(3.seconds)
      result must beAnInstanceOf[ApprovalRejected]
    }

    "update the given auditbulk and save it but not trigger anything if the approval status is Rejected" in new AkkaTestkitSpecs2Support {
      implicit val ec = system.dispatcher

      val bulkid = UUID.fromString("D88073AE-1B13-4679-801D-2EB15BAE0A57")
      val mockedGlacierRestoreActor = TestProbe()
      val mockedAuditEntryDAO = mock[AuditEntryDAO]
      val mockedAuditBulkDAO = mock[AuditBulkDAO]
      val mockedUserProfileDAO = mock[UserProfileDAO]
      val mockedLightboxEntryDAO = mock[LightboxEntryDAO]

      mockedAuditBulkDAO.saveSingle(any) returns Future(Right(mock[RequestSuccess[UpdateResponse]]))

      val actor = system.actorOf(Props(new AuditApprovalActor(mockedAuditEntryDAO, mockedAuditBulkDAO, mockedUserProfileDAO, mockedLightboxEntryDAO, mockedGlacierRestoreActor.ref)))

      val testAuditBulk = AuditBulk(bulkid,"test-lightbox-bulk","some/base/path",ApprovalStatus.Pending,"joe.smith",ZonedDateTime.now(),"some reason",None)
      val adminsUserProfile = UserProfile("some-admin@thecompany.org",true,Seq(),false,None)

      val result = Await.result((actor ? AdminApprovalOverride(testAuditBulk,adminsUserProfile,ApprovalStatus.Rejected,"automated testing")).mapTo[AAMMsg], 10 seconds)
      there was one(mockedAuditBulkDAO).saveSingle(any)
      mockedGlacierRestoreActor.expectNoMessage(3.seconds)
      result must beAnInstanceOf[ApprovalRejected]
    }
  }
}
