package controllers

import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.services.s3.AmazonS3
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, SourceType}
import models.JobReportSuccess
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

object JobControllerHelper {
  private val logger = Logger(getClass)
  def thumbnailJobOriginalMedia(jobDesc:JobModel)(implicit esClient:HttpClient,indexer:Indexer, ec:ExecutionContext) = jobDesc.sourceType match {
    case SourceType.SRC_MEDIA=>
      indexer.getById(jobDesc.sourceId).map(result=>Right(result))
    case SourceType.SRC_PROXY=>
      Future(Left("need original media!"))
    case SourceType.SRC_THUMBNAIL=>
      Future(Left("need original media!"))
  }

  def updateProxyRef(report:JobReportSuccess, archiveEntry:ArchiveEntry, proxyLocationDAO:ProxyLocationDAO, defaultRegion:String)(implicit s3Client:AmazonS3, dynamoClient:DynamoClient, ec:ExecutionContext) = {
    val rgn = archiveEntry.region.getOrElse(defaultRegion)
    ProxyLocation
      .fromS3(proxyUri = report.output, mainMediaUri = s"s3://${archiveEntry.bucket}/${archiveEntry.path}",Some(ProxyType.THUMBNAIL))
      .flatMap({
        case Left(err) =>
          logger.error(s"Could not get proxy location: $err")
          Future(Left(err))
        case Right(proxyLocation) =>
          logger.info("Saving proxy location...")
          proxyLocationDAO.saveProxy(proxyLocation).map({
            case None =>
              Right("Updated with no data back")
            case Some(Left(err)) =>
              Left(err.toString)
            case Some(Right(updatedLocation)) =>
              logger.info(s"Updated location: $updatedLocation")
              Right(s"Updated $updatedLocation")
          })
      })
  }
}
