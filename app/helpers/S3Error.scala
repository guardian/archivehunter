package helpers

import scala.util.Try

case class S3Error (code:String, message:String, requestId:String, hostId:String)

object S3Error extends ((String, String,String,String)=>S3Error) {
  def fromMap(data:Map[String,String]):Try[S3Error] = Try {
    new S3Error(data("Code"), data("Message"), data("RequestId"), data("HostId"))
  }
}