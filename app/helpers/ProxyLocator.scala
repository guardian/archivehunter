package helpers

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ListObjectsV2Request
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation}
import models.ScanTargetDAO
import play.api.Logger
import collection.JavaConverters._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object ProxyLocator {
  private val logger = Logger(getClass)
  def checkBucketLocation(bucket:String, key:String)(implicit s3Client:AmazonS3) = Future {
    Tuple2(bucket, s3Client.doesObjectExist(bucket,key))
  }

  /**
    * searches a list of potential media buckets for a matching media file.  For every valid one found (should be one!)
    * return a ProxyLocation object
    * @param proxyKey Key for the proxy that we are looking for
    * @param proxyBucket bucket that the proxy lives in
    * @param potentialMediaBuckets sequence of names for buckets to search
    * @param s3Client implicitly provided s3 client object
    * @return a Future, containing a sequence of [[ProxyLocation]] objects, one for each valid entry from potentialMediaBuckets
    */
//  def findMediaLocations(proxyKey:String, proxyBucket:String, potentialMediaBuckets:Seq[String])(implicit s3Client:AmazonS3) = {
//    val results = Future.sequence(potentialMediaBuckets.map(bucket=>checkBucketLocation(bucket, proxyKey)))
//
//    val existingEntries = results.map(_.filter(_._2))
//    existingEntries.flatMap(
//      validLocations=>Future.sequence(
//        validLocations.map(result=>ProxyLocation.fromS3(proxyBucket, proxyKey, result._1))
//      )
//    )
//  }

  private val fileEndingRegex = "^(.*)\\..*$".r

  def stripFileEnding(filepath:String) = {
    try {
      val fileEndingRegex(stripped) = filepath
      stripped
    } catch {
      case ex:MatchError=>
        filepath  //if there is no file extension, then return the lot.
    }
  }

  /**
    * see if there is a proxy for the given [[ArchiveEntry]], assuming none exists in the dynamo table
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
        Future.sequence(potentialProxies.getObjectSummaries.asScala.toSeq.map(summary=>{
          ProxyLocation.fromS3(summary.getBucketName,summary.getKey, entry.bucket, entry.path)
        }))
    })

  }
}
