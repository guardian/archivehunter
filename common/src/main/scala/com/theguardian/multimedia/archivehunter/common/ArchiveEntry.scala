package com.theguardian.multimedia.archivehunter.common

import java.time.{ZoneId, ZonedDateTime}

import com.amazonaws.services.s3.AmazonS3Client
import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import io.circe._, io.circe.generic.semiauto._
//needed to serialize/deserialize ZonedDateTime, even if Intellij says it's not
import io.circe.java8.time._
import java.util.Base64

object ArchiveEntry {
  private def getFileExtension(str: String):Option[String] = {
    val tokens = str.split("\\.(?=[^\\.]+$)")
    if(tokens.length==2){
      Some(tokens(1))
    } else {
      None
    }
  }

  /**
    * Calculates an ID unique to this bucket/path combination, that is not longer than the elasticsearch limit
    * @param bucket bucket that the file is coming from
    * @param key path to the file in `bucket`
    */
  def makeDocId(bucket: String, key:String):String = {
    val maxIdLength=512
    val encoder = java.util.Base64.getEncoder

    val initialString = bucket + ":" + key
    if(initialString.length<=maxIdLength){
      encoder.encodeToString(initialString.toCharArray.map(_.toByte))
    } else {
      /* I figure that the best way to get something that should be unique for a long path is to chop out the middle */
      val chunkLength = initialString.length/3
      val stringParts = initialString.grouped(chunkLength).toList
      val midSectionLength = maxIdLength - chunkLength*2  //FIXME: what if chunkLength*2>512??
      val finalString = stringParts.head + stringParts(1).substring(0, midSectionLength) + stringParts(2)
      encoder.encodeToString(finalString.toCharArray.map(_.toByte))
    }
  }

  /**
    * Looks up the metadata of a given item in S3 and returns an ArchiveEntry for it.
    * @param bucket bucket that the item resides in
    * @param key path to the item within `bucket`
    * @param client implicitly provided instance of AmazonS3Client to use
    * @return a (blocking) Future, containing a Try which contains an [[ArchiveEntry]] if successful
    */
  def fromS3(bucket: String, key: String)(implicit client:AmazonS3Client):Future[Try[ArchiveEntry]] = Future {
    Try {
      val meta = client.getObjectMetadata(bucket,key)

      ArchiveEntry(makeDocId(bucket, key), bucket, key, getFileExtension(key), meta.getContentLength, ZonedDateTime.ofInstant(meta.getLastModified.toInstant, ZoneId.systemDefault()), meta.getETag, false)
    }
  }
}

case class ArchiveEntry(id:String, bucket: String, path: String, file_extension: Option[String], size: scala.Long, last_modified: ZonedDateTime, etag: String, proxied: Boolean)

trait ArchiveEntryEncoder {
  implicit val archiveEntryEncoder: Encoder[ArchiveEntry] = deriveEncoder[ArchiveEntry]
  implicit val archiveEntryDecoder: Decoder[ArchiveEntry] = deriveDecoder[ArchiveEntry]
}
