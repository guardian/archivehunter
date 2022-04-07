package com.theguardian.multimedia.archivehunter.common

import com.amazonaws.services.s3.model.GetObjectMetadataRequest

import java.time.{ZoneId, ZonedDateTime}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient}
import com.theguardian.multimedia.archivehunter.common.StorageClass.StorageClass
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IndexerError, MediaMetadata}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.logging.log4j.LogManager
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest

import scala.util.{Failure, Success, Try}

object ArchiveEntry extends ((String, String, String, Option[String], Option[String], Option[String], Long, ZonedDateTime, String, MimeType, Boolean, StorageClass, Seq[LightboxIndex], Boolean, Option[MediaMetadata])=>ArchiveEntry) with DocId {
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
  def fromS3Sync(bucket: String, key: String, maybeVersion:Option[String], region:String)(implicit client:S3Client):ArchiveEntry = {
    Try {
      val req = maybeVersion match {
        case None=>HeadObjectRequest.builder().bucket(bucket).key(key).build()
        case Some(ver)=>HeadObjectRequest.builder().bucket(bucket).key(key).versionId(ver).build()
      }
      client.headObject(req)
    } match {
      case Success(meta) =>
        val mimeType = Option(meta.contentType()) match {
          case Some(mimeTypeString) =>
            MimeType.fromString(mimeTypeString) match {
              case Right(mt) => mt
              case Left(error) =>
                logger.warn(error)
                MimeType("application", "octet-stream")
            }
          case None =>
            logger.warn(s"received no content type from S3 for s3://$bucket/$key")
            MimeType("application", "octet-stream")
        }

        val storageClass = Option(meta.storageClassAsString()) match {
          case Some(sc) => sc
          case None =>
            logger.warn(s"s3://$bucket/$key has no storage class! Assuming STANDARD.")
            "STANDARD"
        }
        //prefer the version as obtained from s3 metadata over the one we are given
        val versionToStore = (Option(meta.versionId()), maybeVersion) match {
          case (Some(v), _)=>Some(v)
          case (_, Some(v))=>Some(v)
          case (None, None)=>None
        }
        ArchiveEntry(makeDocId(bucket, key), bucket, key, versionToStore, Some(region), getFileExtension(key), meta.contentLength(), ZonedDateTime.ofInstant(meta.lastModified(), ZoneId.systemDefault()), meta.eTag(), mimeType, proxied = false, StorageClass.withName(storageClass), Seq(), beenDeleted = false, None)
      case Failure(err) =>
        logger.error(s"Could not look up metadata for s3://$bucket/$key in region $region: ${err.getMessage}")
        throw err
    }
  }

  def fromS3(bucket: String, key: String, maybeVersion:Option[String], region:String)(implicit client:S3Client):Future[ArchiveEntry] = Future {
    fromS3Sync(bucket, key, maybeVersion, region)
  }

  def fromIndex(bucket:String, key:String)(implicit indexer:Indexer, httpClient: ElasticClient):Future[ArchiveEntry] =
    indexer.getById(makeDocId(bucket, key))

  def fromIndexFull(bucket:String, key:String)(implicit indexer:Indexer, httpClient: ElasticClient):Future[Either[IndexerError,ArchiveEntry]] =
    indexer.getByIdFull(makeDocId(bucket, key))
}

case class ArchiveEntry(id:String, bucket: String, path: String, maybeVersion:Option[String], region:Option[String], file_extension: Option[String], size: scala.Long, last_modified: ZonedDateTime, etag: String, mimeType: MimeType, proxied: Boolean, storageClass:StorageClass, lightboxEntries:Seq[LightboxIndex], beenDeleted:Boolean=false, mediaMetadata:Option[MediaMetadata]) {
  private val logger = LogManager.getLogger(getClass)
  def getProxy(proxyType: ProxyType.Value)(implicit proxyLocationDAO:ProxyLocationDAO, client:DynamoDbAsyncClient) = proxyLocationDAO.getProxy(id,proxyType)

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
  def registerNewProxy(proxy: ProxyLocation)(implicit proxyLocationDAO: ProxyLocationDAO, indexer:Indexer, client:DynamoDbAsyncClient, httpClient: ElasticClient):Future[ArchiveEntry] = {
    proxyLocationDAO.saveProxy(proxy)
      .map(_=>{
          logger.info(s"Saved proxy info $proxy (no result data)")
          val updated = this.copy(proxied = true)
          indexer.indexSingleItem(updated)
          updated
      })
  }

  def location:String = s"s3://$bucket/$path"
}
