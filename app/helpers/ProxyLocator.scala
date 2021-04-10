package helpers

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ListObjectsV2Request
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ConflictError, ItemNotFound, ScanTargetDAO, UnexpectedReturnCode}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, ProxyLocation, ProxyType}
import org.slf4j.MDC
import play.api.Logger

import collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

object ProxyLocator {
  private val logger = Logger(getClass)
  def checkBucketLocation(bucket:String, key:String)(implicit s3Client:AmazonS3) = Future {
    Tuple2(bucket, s3Client.doesObjectExist(bucket,key))
  }

  private val fileEndingRegex = "^(.*)\\.(.*)$".r

  //list of any likely proxy extensions
  private val knownVideoExtensions = Seq("avi","mp4","mxf","mov","m4v","mpg","mp2","ts","m2ts")
  private val knownAudioExtensions = Seq("wav","aif","aiff","mp3","m2a")
  private val knownImageExtensions = Seq("jpg","jpe","png","tga","tif","tiff","gif")

  def stripFileEnding(filepath:String) = {
    try {
      val fileEndingRegex(stripped,_) = filepath
      stripped
    } catch {
      case _:MatchError=>
        filepath  //if there is no file extension, then return the lot.
    }
  }

  def getFileEnding(filepath:String) = {
    try {
      val fileEndingRegex(_,xtn) = filepath
      Some(xtn)
    } catch {
      case _:MatchError=>
        None
    }
  }

  /**
    * see if there is a proxy for the given [[ArchiveEntry]], assuming none exists in the dynamo table.
    * exclude any .xml files as they are obviously not media
    * @param entry [[ArchiveEntry]] instance
    * @param s3Client implicitly provided AmazonS3 instance
    * @return a Future, containing a Sequence of ProxyLocation objects for each file that matches the given root
    */
  def findProxyLocation(entry:ArchiveEntry)(implicit s3Client:AmazonS3, scanTargetDAO: ScanTargetDAO) = {
    scanTargetDAO.targetForBucket(entry.bucket).flatMap({
      case None=>throw new RuntimeException(s"No scan target for ${entry.bucket}")
      case Some(Left(err))=>throw new RuntimeException(err.toString)  //fail the Future if we get an error. This is picked up with onComplete or recover.
      case Some(Right(st))=>
        val rq = new ListObjectsV2Request()
          .withBucketName(st.proxyBucket)
          .withPrefix(stripFileEnding(entry.path))
        val potentialProxies = s3Client.listObjectsV2(rq)
        logger.debug(s"findProxyLocation got ${potentialProxies.getKeyCount} keys")
        Future.sequence(potentialProxies.getObjectSummaries.asScala
          .map(summary=>{
            if(summary.getKey.endsWith(".xml")) {
              None
            } else {
              Some(ProxyLocation.fromS3(summary.getBucketName, summary.getKey, entry.bucket, entry.path))
            }
          }).collect({case Some(proxyLocation)=>proxyLocation})
        )
    })
  }

  /**
    * determine the proxy type for a given filepath
    * @param filepath
    * @return
    */
  def proxyTypeForExtension(filepath:String) = getFileEnding(filepath).map(_.toLowerCase).map(xtn=>{
    if(knownVideoExtensions.contains(xtn)){
      ProxyType.VIDEO
    } else if(knownAudioExtensions.contains(xtn)){
      ProxyType.AUDIO
    } else if(knownImageExtensions.contains(xtn)){
      ProxyType.THUMBNAIL
    } else {
      ProxyType.UNKNOWN
    }
  })

  /**
    * sets the "proxied" flag on the given item, retrying in case of a version conflict
    * @param sourceId archive entry ID to update
    * @return a Future with either a Left with an error string or a Right with the item ID string
    */
  def setProxiedWithRetry(sourceId:String)(implicit indexer:Indexer, httpClient:ElasticClient):Future[Either[String,String]] =
    indexer.getById(sourceId).flatMap(entry=>{
      println(s"setProxiedWithEntry: sourceId is $sourceId entry is $entry")
      val updatedEntry = entry.copy(proxied = true)
      MDC.put("entry", updatedEntry.toString)
      indexer.indexSingleItem(updatedEntry)
    }).flatMap({
      case Right(value)=>
        logger.debug(s"success returned $value")
        Future(Right(value))
      case Left(ConflictError(_, errorDesc))=> //instead of if(err.error.`type`=="version_conflict_engine_exception")
        logger.warn(s"Elasticsearch version conflict detected for update of $sourceId ($errorDesc), retrying...")
        setProxiedWithRetry(sourceId)
      case Left(err@ UnexpectedReturnCode(_, _, maybeReason))=>
        if(maybeReason.isDefined) MDC.put("error", maybeReason.get)
        logger.error(s"Could not set proxied flag for $sourceId: ${maybeReason.getOrElse("no reason given")}")
        Future(Left(err.toString))
      case Left(ItemNotFound(itemId))=>
        logger.warn(s"Item $itemId could not be found, been deleted already?!")
        Future(Left("Item not found"))
      case Left(otherError)=>
        logger.error(s"Could not set proxied flag: $otherError")
        Future(Left(otherError.toString))
    }).recoverWith({
      case err:Throwable=>
        logger.error(s"Could not set proxied flag on $sourceId: ${err.getMessage}", err)
        Future.failed(err)
    })
}
