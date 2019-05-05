package requests

import java.util.UUID
import models.ApprovalStatus

case class ManualApprovalRequest (bulkId:UUID, approval:ApprovalStatus.Value, notes:String)
