package models

import java.util.Base64

import com.theguardian.multimedia.archivehunter.common.ProxyType
import com.theguardian.multimedia.archivehunter.common.cmn_models.MediaMetadata
import io.circe.{Decoder, Encoder}

object JobReportStatus extends Enumeration {
  val SUCCESS,FAILURE,RUNNING,WARNING = Value
}

trait JobReportStatusEncoder {
  implicit val jobReportStatusEncoder = Encoder.enumEncoder(JobReportStatus)
  implicit val jobReportStatusDecoder = Decoder.enumDecoder(JobReportStatus)
}

case class JobReportNew(status:JobReportStatus.Value, log:Option[String], jobId:String, input:Option[String], output:Option[String], proxyType:Option[ProxyType.Value], metadata:Option[MediaMetadata]) {
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
