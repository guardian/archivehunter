package models

import java.util.Base64

import scala.util.Try

case class JobReportError (status:String, log:String, input:String) extends JobReport {
  /**
    * performs base64 decoding on the log field and returns a new instance with the decoded content
    * @return
    */
  def decodeLog = {
    val dec = Base64.getDecoder
    Try {
      this.copy(log = dec.decode(log).map(_.toChar).mkString)
    }
  }
}
