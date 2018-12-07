package responses

case class PathInfoResponse(status:String, totalHits:Long, totalSize:Long, deletedCounts:Map[String,Long], proxiedCounts:Map[String,Long], typesCount:Map[String,Long])