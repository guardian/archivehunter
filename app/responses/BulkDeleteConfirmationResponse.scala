package responses

case class BulkDeleteConfirmationResponse(status:String, deletedCount: Long, timeTaken: Long)