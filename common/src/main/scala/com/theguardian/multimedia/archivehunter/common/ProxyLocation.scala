package com.theguardian.multimedia.archivehunter.common

import java.net.URI

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.gu.scanamo.DynamoFormat
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import io.circe.{Decoder, Encoder}
import org.apache.logging.log4j.LogManager

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

object ProxyLocation extends DocId {
  private val logger = LogManager.getLogger(getClass)

  private val urlxtractor = "^([\\w\\d]+)://([^\\/]+)/(.*)$".r

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

  protected def getUrlElems(str:String) = {
    val urlxtractor(proto, host, path) = str
    new URI(proto, host, "/"+path, "")
  }

  def fromS3(bucket:String, path:String, mainMediaId:String, meta:ObjectMetadata,proxyType:ProxyType.Value) = {
    new ProxyLocation(mainMediaId, makeDocId(bucket,path), proxyType,bucket,path,StorageClass.withName(meta.getStorageClass))
  }

  def newInS3(proxyLocationString:String, mainMediaId:String, proxyType:ProxyType.Value)(implicit s3Client:AmazonS3) = Try {
    val proxyLocationURI = getUrlElems(proxyLocationString)
    logger.debug(s"newInS3: bucket is ${proxyLocationURI.getHost} path is ${proxyLocationURI.getPath}")
    val fixedPath = if(proxyLocationURI.getPath.startsWith("/")){
      proxyLocationURI.getPath.stripPrefix("/")
    } else {
      proxyLocationURI.getPath
    }

    val storageClassValue = s3Client.getObjectMetadata(proxyLocationURI.getHost,fixedPath).getStorageClass
    new ProxyLocation(mainMediaId, makeDocId(proxyLocationURI.getHost, fixedPath),proxyType, proxyLocationURI.getHost,fixedPath, Some(region), StorageClass.safeWithName(storageClassValue))
  }

  def fromS3(proxyUri: String, mainMediaUri: String, proxyType:Option[ProxyType.Value])(implicit client:AmazonS3):Future[Either[String, ProxyLocation]] = {
    val proxyUriDecoded = getUrlElems(proxyUri)
    val mainMediaUriDecoded = getUrlElems(mainMediaUri)

    if(proxyUriDecoded.getScheme!="s3" || mainMediaUriDecoded.getScheme!="s3"){
      Future(Left("either proxyUri or mainMediaUri is not an S3 url"))
    } else {
      fromS3(proxyUriDecoded.getHost, proxyUriDecoded.getPath.replaceAll("^\\/*",""), mainMediaUriDecoded.getHost, mainMediaUriDecoded.getPath.replaceAll("^\\/*",""), proxyType)
    }
  }

  /**
    * Looks up the metadata of a given item in S3 and returns a ProxyLocation
    * @param bucket bucket that the item resides in
    * @param key path to the item within `bucket`
    * @param client implicitly provided instance of AmazonS3Client to use
    * @return a (blocking) Future, containing a [[ProxyLocation]] if successful
    */
  def fromS3(proxyBucket: String, key: String, mainMediaBucket: String, mediaItemKey:String, proxyType:Option[ProxyType.Value] = None)(implicit client:AmazonS3):Future[Either[String, ProxyLocation]] = Future {
    try {
      logger.debug(s"Looking up proxy location for s3://$proxyBucket/$key")
      val meta = client.getObjectMetadata(proxyBucket, key)
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
          MimeType("application", "octet-stream")
      }

      val storageClass = Option(meta.getStorageClass) match {
        case Some(sc) => sc
        case None =>
          logger.warn(s"s3://$proxyBucket/$key has no storage class! Assuming STANDARD.")
          "STANDARD"
      }
      val pt = proxyType match {
        case None =>
          proxyTypeForMime(mimeType) match {
            case Success(pt) => pt
            case Failure(err) =>
              logger.error(s"Could not determine proxy type for s3://$proxyBucket/$key: ${err.toString}")
              ProxyType.UNKNOWN
          }
        case Some(value) => value
      }
      logger.debug(s"doc ID is ${makeDocId(mainMediaBucket, mediaItemKey)} from $mainMediaBucket and $key")
      Right(new ProxyLocation(makeDocId(mainMediaBucket, mediaItemKey), makeDocId(proxyBucket, key), pt, proxyBucket, key, Some(client.getRegionName), StorageClass.safeWithName(meta.getStorageClass)))
    } catch {
      case ex:Throwable=>
        logger.error("could not find proxyLocation in s3: ", ex)
        Left(ex.toString)
    }
  }

}

case class ProxyLocation (fileId:String, proxyId: String, proxyType: ProxyType.Value, bucketName:String, bucketPath:String, region:Option[String], storageClass: StorageClass.Value) {}
trait ProxyLocationEncoder extends StorageClassEncoder {
  implicit val proxyTypeEncoder = Encoder.enumEncoder(ProxyType)
  implicit val proxyTypeDecoder = Decoder.enumDecoder(ProxyType)

  implicit val proxyTypeFormat = DynamoFormat.coercedXmap[ProxyType.Value,String,IllegalArgumentException](
    input=>ProxyType.withName(input)
  )(
    pt=>pt.toString
  )
}