package controllers

import akka.actor.{ActorRef, ActorSystem}
import com.amazonaws.services.s3.AmazonS3
import com.sksamuel.elastic4s.http.ElasticClient
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.PathCacheExtractor
import com.theguardian.multimedia.archivehunter.common.cmn_models.{PathCacheEntry, PathCacheIndexer, ScanTarget, ScanTargetDAO}
import helpers.InjectableRefresher
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import requests.SpecificImportRequest
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import responses.{GenericErrorResponse, ObjectCreatedResponse}
import services.IngestProxyQueue

import javax.inject.{Inject, Named}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ImportController @Inject()(override val config:Configuration,
                                   override val controllerComponents:ControllerComponents,
                                   override val refresher:InjectableRefresher,
                                   override val wsClient:WSClient,
                                   scanTargetDAO:ScanTargetDAO,
                                   s3ClientMgr: S3ClientManager,
                                   esClientMgr: ESClientManager,
                                   @Named("ingestProxyQueue") ingestProxyQueue:ActorRef)
                                  (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe {

  private val logger = LoggerFactory.getLogger(getClass)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val esClient = esClientMgr.getClient()

  private val indexName = config.get[String]("externalData.indexName")
  private implicit val pathCacheIndexer = new PathCacheIndexer(config.getOptional[String]("externalData.pathCacheIndex").getOrElse("pathcache"), esClient)

  private val indexer = new Indexer(indexName)

  def writePathCacheEntries(newCacheEntries:Seq[PathCacheEntry])
                           (implicit pathCacheIndexer:PathCacheIndexer,  elasticClient:ElasticClient) = {
    import com.sksamuel.elastic4s.http.ElasticDsl._
    import io.circe.generic.auto._
    import com.sksamuel.elastic4s.circe._

    Future.sequence(
      newCacheEntries.map(entry=>elasticClient.execute(
        update(entry.collection + entry.key) in s"${pathCacheIndexer.indexName}/pathcache" docAsUpsert entry
      ))
    ).map(responses=>{
      val failures = responses.filter(_.isError)
      if(failures.nonEmpty) {
        logger.error(s"${failures.length} path cache entries failed: ")
        failures.foreach(err=>logger.error(err.error.reason))
      }
      println(s"${failures.length} / ${newCacheEntries.length} path cache entries failed")
    })
  }

  def addToPathCache(importRequest:SpecificImportRequest) = {
    //build a list of entries to add to the path cache
    val pathParts = importRequest.itemPath.split("/").init  //the last element is the filename, which we are not interested in.

    val newCacheEntries = if(pathParts.isEmpty) {
      Seq()
    } else {
      PathCacheExtractor.recursiveGenerateEntries(pathParts.init, pathParts.last, pathParts.length, importRequest.collectionName)
    }
    logger.info(s"going to update ${newCacheEntries.length} path cache entries")
    writePathCacheEntries(newCacheEntries)
  }

  def importFromPath = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SpecificImportRequest] match {
      case Left(err)=>
        Future(BadRequest(GenericErrorResponse("bad_request",err.toString()).asJson))
      case Right(importRequest)=>
        scanTargetDAO.targetForBucket(importRequest.collectionName).flatMap({
          case None=>
            Future(BadRequest(GenericErrorResponse("not_found", "The given collection does not exist").asJson))
          case Some(Left(err))=>
            logger.error(s"Could not look up scan target for bucket: $err")
            Future(InternalServerError(GenericErrorResponse("db_error","Could not look up scan target").asJson))
          case Some(Right(scanTarget))=>
            if(scanTarget.enabled) {
              implicit val s3client:AmazonS3 = s3ClientMgr.getClient(awsProfile)
              if(s3client.doesObjectExist(scanTarget.bucketName, importRequest.itemPath)) {
                val entry = ArchiveEntry.fromS3Sync(scanTarget.bucketName, importRequest.itemPath, scanTarget.region)
                indexer.indexSingleItem(entry).flatMap({
                  case Left(err)=>
                    logger.error(s"Could not index new item $entry: $err")
                    Future(InternalServerError(GenericErrorResponse("index_error","Could not index new item, see server logs").asJson))
                  case Right(newId)=>
                    logger.info(s"Registered new item with ID $newId, adding to path cache")
                    ingestProxyQueue ! IngestProxyQueue.CheckRegisteredProxy
                    ingestProxyQueue ! IngestProxyQueue.CheckRegisteredThumb
                    ingestProxyQueue ! IngestProxyQueue.StartAnalyse
                    addToPathCache(importRequest).map(_=>{
                      Ok(ObjectCreatedResponse("ok","item",newId).asJson)
                    }).recover({
                      case err:Throwable=>
                        logger.error(s"Could not add to path cache, but did create item: $err")
                        Ok(ObjectCreatedResponse("ok","item",newId).asJson)
                    })
                })
              } else {
                Future(NotFound(GenericErrorResponse("not_found","The given file does not exist").asJson))
              }
            } else {
              Future(BadRequest(GenericErrorResponse("disabled","This scan target is disabled").asJson))
            }
        }).recover({
          case err:Throwable=>
            logger.error(s"Specific import request crashed: ${err.getMessage}", err)
            InternalServerError(GenericErrorResponse("error","The import process failed, please see server logs for details").asJson)
        })

    }
  }
}
