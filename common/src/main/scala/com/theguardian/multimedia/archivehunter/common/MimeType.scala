package com.theguardian.multimedia.archivehunter.common


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
