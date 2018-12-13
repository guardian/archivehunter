package responses

case class ErrorListResponse(status:String,detail:String, errors:List[String])
