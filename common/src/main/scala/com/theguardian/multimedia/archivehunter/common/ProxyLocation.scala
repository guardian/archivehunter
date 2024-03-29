package com.theguardian.multimedia.archivehunter.common

import java.net.URI
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import org.scanamo.DynamoFormat
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import io.circe.{Decoder, Encoder}
import org.apache.logging.log4j.LogManager
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, HeadObjectResponse}

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

  def fromS3(bucket:String, path:String, mainMediaId:String, meta:HeadObjectResponse, maybeRegion:Option[String], proxyType:ProxyType.Value) = {
    val storageClass = Option(meta.storageClassAsString()).map(actualClass=>StorageClass.withName(actualClass)).getOrElse(StorageClass.STANDARD)
    new ProxyLocation(mainMediaId, makeDocId(bucket,path), proxyType,bucket,path,maybeRegion,storageClass)
  }

  def newInS3(proxyLocationString:String, mainMediaId:String, region:String, proxyType:ProxyType.Value)(implicit s3Client:S3Client) = Try {
    val proxyLocationURI = getUrlElems(proxyLocationString)
    logger.debug(s"newInS3: bucket is ${proxyLocationURI.getHost} path is ${proxyLocationURI.getPath}")
    val fixedPath = if(proxyLocationURI.getPath.startsWith("/")){
      proxyLocationURI.getPath.stripPrefix("/")
    } else {
      proxyLocationURI.getPath
    }

    val storageClassValue = s3Client.headObject(HeadObjectRequest.builder().bucket(proxyLocationURI.getHost).key(fixedPath).build()).storageClassAsString()
    new ProxyLocation(mainMediaId, makeDocId(proxyLocationURI.getHost, fixedPath),proxyType, proxyLocationURI.getHost,fixedPath, Some(region), StorageClass.safeWithName(storageClassValue))
  }

  def fromS3(proxyUri: String, mainMediaUri: String, proxyType:Option[ProxyType.Value], region:Region)(implicit client:S3Client):Future[Either[String, ProxyLocation]] = {
    val proxyUriDecoded = getUrlElems(proxyUri)
    val mainMediaUriDecoded = getUrlElems(mainMediaUri)

    if(proxyUriDecoded.getScheme!="s3" || mainMediaUriDecoded.getScheme!="s3"){
      Future(Left("either proxyUri or mainMediaUri is not an S3 url"))
    } else {
      fromS3(proxyUriDecoded.getHost, proxyUriDecoded.getPath.replaceAll("^\\/*",""), mainMediaUriDecoded.getHost, mainMediaUriDecoded.getPath.replaceAll("^\\/*",""), proxyType, region)
    }
  }

  /**
    * Looks up the metadata of a given item in S3 and returns a ProxyLocation
    * @param proxyBucket bucket that the item resides in
    * @param key path to the item within `bucket`
    * @param client implicitly provided instance of AmazonS3Client to use
    * @return a (blocking) Future, containing a [[ProxyLocation]] if successful
    */
  def fromS3(proxyBucket: String, key: String, mainMediaBucket: String, mediaItemKey:String, proxyType:Option[ProxyType.Value] = None, region:Region)(implicit client:S3Client):Future[Either[String, ProxyLocation]] = Future {
    try {
      logger.debug(s"Looking up proxy location for s3://$proxyBucket/$key")
      val meta = client.headObject(HeadObjectRequest.builder().bucket(proxyBucket).key(key).build())
      val mimeType = Option(meta.contentType()) match {
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

      val pt = proxyType match {
        case None =>
          proxyTypeForMime(mimeType) match {
            case Success(pt) => pt
            case Failure(err) =>
              logger.error(s"Could not determine proxy type for s3://$proxyBucket/$key: ${err.toString}")
              if(key.endsWith(".mp4")) { //go through the obvious file extensions, as a fallback
                ProxyType.VIDEO
              } else if(key.endsWith(".mp3")) {
                ProxyType.AUDIO
              } else if(key.endsWith(".jpg") || key.endsWith(".jpeg")) {
                ProxyType.THUMBNAIL
              } else {
                ProxyType.UNKNOWN
              }
          }
        case Some(value) => value
      }
      logger.debug(s"doc ID is ${makeDocId(mainMediaBucket, mediaItemKey)} from $mainMediaBucket and $key")
      Right(new ProxyLocation(makeDocId(mainMediaBucket, mediaItemKey), makeDocId(proxyBucket, key), pt, proxyBucket, key, Some(region.toString), StorageClass.safeWithName(meta.storageClassAsString())))
    } catch {
      case ex:Throwable=>
        logger.error("could not find proxyLocation in s3: ", ex)
        Left(ex.toString)
    }
  }

}

case class ProxyLocation (fileId:String, proxyId: String, proxyType: ProxyType.Value, bucketName:String, bucketPath:String, region:Option[String], storageClass: StorageClass.Value) {}
trait ProxyLocationEncoder extends StorageClassEncoder {
  implicit val proxyTypeEncoder = Encoder.encodeEnumeration(ProxyType)
  implicit val proxyTypeDecoder = Decoder.decodeEnumeration(ProxyType)

  implicit val proxyTypeFormat = DynamoFormat.coercedXmap[ProxyType.Value,String,IllegalArgumentException](
    input=>ProxyType.withName(input),
    pt=>pt.toString
  )
}