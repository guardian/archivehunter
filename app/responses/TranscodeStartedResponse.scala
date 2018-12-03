package responses

case class TranscodeStartedResponse(status:String, jobId:String, transcodeId:Option[String])
