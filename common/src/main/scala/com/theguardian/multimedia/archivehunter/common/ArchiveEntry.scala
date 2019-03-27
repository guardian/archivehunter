package com.theguardian.multimedia.archivehunter.common

import java.time.{ZoneId, ZonedDateTime}

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.StorageClass.StorageClass
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IndexerError, MediaMetadata}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.logging.log4j.LogManager
//needed to serialize/deserialize ZonedDateTime, even if Intellij says it's not
import io.circe.java8.time._
import io.circe.generic.semiauto._

object ArchiveEntry extends ((String, String, String, Option[String], Option[String], Long, ZonedDateTime, String, MimeType, Boolean, StorageClass, Seq[LightboxIndex], Boolean, Option[MediaMetadata])=>ArchiveEntry) with DocId {
  private val logger = LogManager.getLogger(getClass)

  def getFileExtension(str: String):Option[String] = {
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
    * @return a (blocking) Future, containing an [[ArchiveEntry]] if successful
    */
  def fromS3Sync(bucket: String, key: String, region:String)(implicit client:AmazonS3):ArchiveEntry = {
    val meta = client.getObjectMetadata(bucket,key)
    val mimeType = Option(meta.getContentType) match {
      case Some(mimeTypeString) =>
        MimeType.fromString(mimeTypeString) match {
          case Right(mt) => mt
          case Left(error) =>
            logger.warn(error)
            MimeType("application", "octet-stream")
        }
      case None =>
        logger.warn(s"received no content type from S3 for s3://$bucket/$key")
        MimeType("application","octet-stream")
    }

    val storageClass = Option(meta.getStorageClass) match {
      case Some(sc)=>sc
      case None=>
        logger.warn(s"s3://$bucket/$key has no storage class! Assuming STANDARD.")
        "STANDARD"
    }
    ArchiveEntry(makeDocId(bucket, key), bucket, key, Some(region), getFileExtension(key), meta.getContentLength, ZonedDateTime.ofInstant(meta.getLastModified.toInstant, ZoneId.systemDefault()), meta.getETag, mimeType, proxied = false, StorageClass.withName(storageClass), Seq(), beenDeleted = false, None)
  }

  def fromS3(bucket: String, key: String, region:String)(implicit client:AmazonS3):Future[ArchiveEntry] = Future {
    fromS3Sync(bucket, key,region)
  }

  def fromIndex(bucket:String, key:String)(implicit indexer:Indexer, httpClient: HttpClient):Future[ArchiveEntry] =
    indexer.getById(makeDocId(bucket, key))

  def fromIndexFull(bucket:String, key:String)(implicit indexer:Indexer, httpClient: HttpClient):Future[Either[IndexerError,ArchiveEntry]] =
    indexer.getByIdFull(makeDocId(bucket, key))
}

case class ArchiveEntry(id:String, bucket: String, path: String, region:Option[String], file_extension: Option[String], size: scala.Long, last_modified: ZonedDateTime, etag: String, mimeType: MimeType, proxied: Boolean, storageClass:StorageClass, lightboxEntries:Seq[LightboxIndex], beenDeleted:Boolean=false, mediaMetadata:Option[MediaMetadata]) {
  private val logger = LogManager.getLogger(getClass)
  def getProxy(proxyType: ProxyType.Value)(implicit proxyLocationDAO:ProxyLocationDAO, client:DynamoClient) = proxyLocationDAO.getProxy(id,proxyType)

  /**
    * register a new proxy against this item.  This operation consists of saving the given proxy id to the proxies table,
    * updating the "hasProxy" flag and saving this to the index
    * @param proxy [[ProxyLocation]] object to register
    * @param proxyLocationDAO implicitly provided [[ProxyLocationDAO]] object to access save functions
    * @param indexer implicitly provided [[Indexer]] object to access indexing save
    * @param client implicitly provided Alpakka DynamoClient object to allow db access
    * @param httpClient implicitly provided elastic4s HttpClient object to allow index save access
    * @return a Future, containing an [[ArchiveEntry]] representing the updated record.  This future fails on error.
    */
  def registerNewProxy(proxy: ProxyLocation)(implicit proxyLocationDAO: ProxyLocationDAO, indexer:Indexer, client:DynamoClient, httpClient: HttpClient):Future[ArchiveEntry] = {
    proxyLocationDAO.saveProxy(proxy)
      .map({
        case Some(Right(result))=>
          logger.info(s"Saved proxy info $proxy")
          val updated = this.copy(proxied = true)
          indexer.indexSingleItem(updated)
          updated
        case None=>
          logger.info(s"Saved proxy info $proxy (no result data)")
          val updated = this.copy(proxied = true)
          indexer.indexSingleItem(updated)
          updated
        case Some(Left(err))=>throw new RuntimeException(err.toString)
      })
  }
}
