package models

import java.util.Base64

import io.circe.{Decoder, Encoder}

case class JobReportNew(status:String, log:Option[String], jobId:String, input:Option[String], output:Option[String], metadata:Option[String]) {
  /**
    * performs base64 decoding on the log field and returns the result.
    * @return None if there was no log field. Some(Right(data)) if there was log data or Some(Left(errorString)) if there was an error
    */
  def decodedLog:Option[Either[String,String]] = log.map(actualLog=>{
    val dec = Base64.getDecoder
    try {
      Right(dec.decode(actualLog).map(_.toChar).mkString)
    } catch {
      case ex:Exception=>
        Left(ex.toString)
    }
  })
}
