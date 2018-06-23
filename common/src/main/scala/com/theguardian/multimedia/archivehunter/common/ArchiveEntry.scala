package com.theguardian.multimedia.archivehunter.common

import java.time.{ZoneId, ZonedDateTime}

import com.amazonaws.services.s3.AmazonS3Client

import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

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
    * Looks up the metadata of a given item in S3 and returns an ArchiveEntry for it.
    * @param bucket bucket that the item resides in
    * @param key path to the item within `bucket`
    * @param client implicitly provided instance of AmazonS3Client to use
    * @return a (blocking) Future, containing a Try which contains an [[ArchiveEntry]] if successful
    */
  def fromS3(bucket: String, key: String)(implicit client:AmazonS3Client):Future[Try[ArchiveEntry]] = Future {
    Try {
      val meta = client.getObjectMetadata(bucket,key)

      ArchiveEntry(bucket, key, getFileExtension(key), meta.getContentLength, ZonedDateTime.ofInstant(meta.getLastModified.toInstant, ZoneId.systemDefault()), meta.getETag, false)
    }
  }
}

case class ArchiveEntry(bucket: String, path: String, file_extension: Option[String], size: Long, last_modified: ZonedDateTime, etag: String, proxied: Boolean)