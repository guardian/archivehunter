package com.theguardian.multimedia.archivehunter.common

//import software.amazon.awssdk.core.ResponseBytes
//import software.amazon.awssdk.services.s3.S3AsyncClient
//import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}
//import software.amazon.awssdk.core.async.AsyncResponseTransformer
//import org.apache.logging.log4j.LogManager
//import org.apache.logging.log4j.Logger
//import scala.compat.java8.FutureConverters
//import scala.concurrent.Future
//import scala.concurrent.ExecutionContext.Implicits.global

object MimeType {
  /**
    * returns a MIME type by checking the content type of an object in s3.
    * Since we're using 1.x of the SDK for java this is unfortunately a blocking operation
    * @param bucketName
    * @param key
    * @param client
    * @return
    */

  def fromString(mimeString: String):Either[String, MimeType] = {
    val majorMinor = mimeString.split("/")
    if(majorMinor.length!=2){
      Left(s"$mimeString does not look like a MIME type")
    } else {
      Right(MimeType(majorMinor.head,majorMinor(1)))
    }
  }
}

case class MimeType (major:String, minor:String) {
  override def toString: String = s"$major/$minor"
}
