package services

import akka.actor.Actor
import com.gu.scanamo.error.DynamoReadError
import com.sksamuel.elastic4s.http.RequestFailure
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxEntryDAO}
import javax.inject.Inject
import models.{ApprovalStatus, AuditApproval, AuditBulk, AuditBulkDAO, AuditEntryClass, AuditEntryDAO, UserProfile, UserProfileDAO}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

object AuditApprovalActor {
  trait AAMMsg

  //input messages
  case class AutomatedApprovalCheck(auditBulk:AuditBulk) extends AAMMsg
  case class AdminApprovalOverride(auditBulk:AuditBulk, approver:UserProfile, newStatus:ApprovalStatus.Value, notes:String) extends  AAMMsg
  //internal messages

  //output messages
  case class ApprovalGranted(message:String) extends AAMMsg
  case class ApprovalRejected(message:String) extends AAMMsg
  case class ApprovalPending(message:String) extends AAMMsg

}

import scala.concurrent.ExecutionContext.Implicits.global

class AuditApprovalActor @Inject() (auditEntryDAO: AuditEntryDAO, auditBulkDAO: AuditBulkDAO, userProfileDAO: UserProfileDAO,lightboxEntryDAO: LightboxEntryDAO) extends Actor {
  import AuditApprovalActor._
  private val logger = Logger(getClass)

  def updateAuditBulk(current:AuditBulk, status:ApprovalStatus.Value, admin:Option[String], comment:String) = {
    val updatedAuditBulk = current.copy(approvalStatus = status, approval=Some(AuditApproval(admin,comment)))
    auditBulkDAO.saveSingle(updatedAuditBulk).map({
      case Right(_)=>  //don't bother waiting on this, return immediately
      case Left(err)=>
        logger.error(s"Could not save updated audit bulk record: $err")
    })
  }

  override def receive: Receive = {
    /**
      * perform an automated check.  If the size of the restore is less than the user's current limit, then allow it.
      * the bulk audit entry must have been set up prior to calling this.
      */
    case AutomatedApprovalCheck(auditBulk:AuditBulk)=>
      val originalSender = sender()

      logger.info(s"in AutomatedApprovalCheck for $auditBulk")
      val futureSeq = Seq(
        auditEntryDAO.totalSizeForBulk(auditBulk.bulkId, AuditEntryClass.Restore),
        userProfileDAO.userProfileForEmail(auditBulk.requestedBy)
      )

      Future.sequence(futureSeq).map(results=>{
        val auditSizeResult = results.head.asInstanceOf[Either[RequestFailure,Double]]
        val userProfileResult = results(1).asInstanceOf[Option[Either[DynamoReadError, UserProfile]]]

        auditSizeResult match {
          case Left(err) =>
            logger.error(s"Could not check size of bulk for ${auditBulk.bulkId}: $err")
            originalSender ! ApprovalPending("Could not check size of bulk, requires manual check")
          case Right(actualSize) =>
            userProfileResult match {
              case None=>
                logger.error(s"Could not automatically approve ${auditBulk.bulkId}, user ${auditBulk.requestedBy} does not exist")
                originalSender ! ApprovalPending(s"Could not automatically approve ${auditBulk.bulkId}, user ${auditBulk.requestedBy} does not exist")
              case Some(Left(err))=>
                logger.error(s"Could not automatically approve ${auditBulk.bulkId}, Dynamo lookup error: $err")
                originalSender ! ApprovalPending(s"Could not automatically approve due to a database error")
              case Some(Right(userProfile))=>
                userProfile.perRestoreQuota match {
                  case None=>
                    logger.info(s"User restore limit is not set for ${userProfile.userEmail}")
                    originalSender ! ApprovalPending(s"You need administrative approval to perform this action")
                  case Some(limit)=>
                    if(limit>actualSize.toLong){
                      logger.info(s"Request ${auditBulk.bulkId} of size ${actualSize.toLong} is less than user's limit of $limit, allowing")
                      updateAuditBulk(auditBulk, ApprovalStatus.Allowed, None, s"This is below user limit of $limit")
                      originalSender ! ApprovalGranted(s"Automatically approved as below user limit of $limit")
                    } else {
                      logger.info(s"Request ${auditBulk.bulkId} of size ${actualSize.toLong} is more than user's limit of $limit, blocking")
                      updateAuditBulk(auditBulk, ApprovalStatus.Rejected, None, s"${actualSize.toLong} is above user limit of $limit")
                      originalSender ! ApprovalRejected(s"This is above user limit of $limit")
                    }
                }
            }
        }
      })

    /**
      *
      */
    case AdminApprovalOverride(auditBulk:AuditBulk, approver:UserProfile, newStatus:ApprovalStatus.Value, notes:String)=>

  }
}
