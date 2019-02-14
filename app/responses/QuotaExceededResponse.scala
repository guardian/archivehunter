package responses

case class QuotaExceededResponse (status:String, error:String, requiredQuota:Long, actualQuota: Long)