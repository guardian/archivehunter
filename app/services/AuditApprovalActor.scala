package services

import akka.actor.Actor
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxEntryDAO}
import javax.inject.Inject
import models.{AuditBulk, AuditBulkDAO, AuditEntryDAO}

object AuditApprovalActor {
  trait AAMMsg

  //input messages
  case class AutomatedApprovalCheck(auditBulk:AuditBulk) extends AAMMsg

  //internal messages

  //output messages
  case object ApprovalGranted extends AAMMsg
  case object ApprovalRejected extends AAMMsg
  case object ApprovalPending extends AAMMsg

}

class AuditApprovalActor @Inject() (auditEntryDAO: AuditEntryDAO, auditBulkDAO: AuditBulkDAO, lightboxEntryDAO: LightboxEntryDAO) extends Actor {
  import AuditApprovalActor._

  override def receive: Receive = {
    case AutomatedApprovalCheck(auditBulk:AuditBulk)=>
      val originalSender = sender()

      auditEntryDAO.totalSizeForBulk(auditBulk.bulkId)
  }
}
