package com.theguardian.multimedia.archivehunter.common

import java.time.{ZoneId, ZonedDateTime}

import com.amazonaws.services.s3.AmazonS3
import com.gu.scanamo.DynamoFormat
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry.{getClass, getFileExtension, logger, makeDocId}
import org.apache.logging.log4j.LogManager

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

object ProxyLocation extends DocId {
  private val logger = LogManager.getLogger(getClass)

  /**
    * return a ProxyType value that seems to fit the given MIME type
    * @param mimeType [[MimeType]] instance
    */
  def proxyTypeForMime(mimeType: MimeType):Try[ProxyType.Value] = {
    mimeType.major match {
      case "video"=>Success(ProxyType.VIDEO)
      case "audio"=>Success(ProxyType.AUDIO)
      case "image"=>Success(ProxyType.THUMBNAIL)
      case _=>Failure(new RuntimeException(s"$mimeType does not have a recognised major type"))
    }
  }

  /**
    * Looks up the metadata of a given item in S3 and returns an ArchiveEntry for it.
    * @param bucket bucket that the item resides in
    * @param key path to the item within `bucket`
    * @param client implicitly provided instance of AmazonS3Client to use
    * @return a (blocking) Future, containing an [[ArchiveEntry]] if successful
    */
  def fromS3(proxyBucket: String, key: String, mainMediaBucket: String, proxyType:Option[ProxyType.Value] = None)(implicit client:AmazonS3):Future[ProxyLocation] = Future {
    val meta = client.getObjectMetadata(proxyBucket,key)
    val mimeType = Option(meta.getContentType) match {
      case Some(mimeTypeString) =>
        MimeType.fromString(mimeTypeString) match {
          case Right(mt) => mt
          case Left(error) =>
            logger.warn(error)
            MimeType("application", "octet-stream")
        }
      case None =>
        logger.warn(s"received no content type from S3 for s3://$proxyBucket/$key")
        MimeType("application","octet-stream")
    }

    val storageClass = Option(meta.getStorageClass) match {
      case Some(sc)=>sc
      case None=>
        logger.warn(s"s3://$proxyBucket/$key has no storage class! Assuming STANDARD.")
        "STANDARD"
    }
    val pt = proxyType match {
      case None=>
        proxyTypeForMime(mimeType) match {
          case Success(pt)=>pt
          case Failure(err)=>
            logger.error(s"Could not determine proxy type for s3://$proxyBucket/$key: ${err.toString}")
            ProxyType.UNKNOWN
        }
      case Some(value)=>value
    }
    logger.debug(s"doc ID is ${makeDocId(mainMediaBucket, key)} from $mainMediaBucket and $key")
    new ProxyLocation(makeDocId(mainMediaBucket, key), pt, proxyBucket, key, StorageClass.safeWithName(meta.getStorageClass))
  }

}

case class ProxyLocation (fileId:String, proxyType: ProxyType.Value, bucketName:String, bucketPath:String, storageClass: StorageClass.Value)

trait ProxyLocationEncoder extends StorageClassEncoder {
  implicit val proxyTypeFormat = DynamoFormat.coercedXmap[ProxyType.Value,String,IllegalArgumentException](
    input=>ProxyType.withName(input)
  )(
    pt=>pt.toString
  )
}