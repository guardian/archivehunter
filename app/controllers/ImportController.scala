package controllers

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.Materializer
import auth.{BearerTokenAuth, Security}
import com.amazonaws.services.s3.AmazonS3
import io.circe.generic.auto._
import com.gu.scanamo.error.DynamoReadError
import com.sksamuel.elastic4s.http.ElasticClient
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, ProxyLocation, ProxyLocationDAO, ProxyTypeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.PathCacheExtractor
import com.theguardian.multimedia.archivehunter.common.cmn_models.{PathCacheEntry, PathCacheIndexer, ScanTarget, ScanTargetDAO}
import helpers.ProxyLocator
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents, Result}
import requests.{ProxyImportRequest, SpecificImportRequest}
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import play.api.cache.SyncCacheApi
import responses.{GenericErrorResponse, ObjectCreatedResponse}
import services.IngestProxyQueue

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

@Singleton
class ImportController @Inject()(override val config:Configuration,
                                   override val controllerComponents:ControllerComponents,
                                   scanTargetDAO:ScanTargetDAO,
                                 proxyLocationDAO:ProxyLocationDAO,
                                   s3ClientMgr: S3ClientManager,
                                   esClientMgr: ESClientManager,
                                   override val bearerTokenAuth: BearerTokenAuth,
                                   override val cache:SyncCacheApi,
                                    ddbClientMgr:DynamoClientManager,
                                   @Named("ingestProxyQueue") ingestProxyQueue:ActorRef)
                                  (implicit actorSystem:ActorSystem, mat:Materializer)
  extends AbstractController(controllerComponents) with Security with Circe with ProxyTypeEncoder {

  override protected val logger = LoggerFactory.getLogger(getClass)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val esClient = esClientMgr.getClient()

  private val indexName = config.get[String]("externalData.indexName")
  private implicit val pathCacheIndexer = new PathCacheIndexer(config.getOptional[String]("externalData.pathCacheIndex").getOrElse("pathcache"), esClient)

  private implicit val ddbAkkaClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)
  private implicit val indexer = new Indexer(indexName)

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

  def importFromPath = IsAuthenticatedAsync(circe.json(2048)) { uid=> request=>
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

  def proxyBucketForMaybeScanTarget(tgt:Option[Either[DynamoReadError, ScanTarget]]):Future[String] = tgt match {
    case None=>
      Future.failed(new RuntimeException("No scan target existed for the item's bucket!"))
    case Some(Left(err))=>
      Future.failed(new RuntimeException(err.toString))
    case Some(Right(actualTarget))=>
      Future(actualTarget.proxyBucket)
  }

  /**
    * Wraps the "saveProxy" method to make it a bit nicer for composition
    * @param newRecord record to save
    * @return a Future which completes with the saved record on success and fails on error
    */
  private def wrapSaveProxy(newRecord:ProxyLocation) = {
    proxyLocationDAO.saveProxy(newRecord).flatMap({
      case None=>Future(newRecord)
      case Some(Left(err))=>
        Future.failed(new RuntimeException(err.toString))
      case Some(Right(rec))=>
        Future(rec)
    })
  }

  /**
    * Ensures that the requested proxy exists and if so 'connects' it as a proxy to the given item
    * @param importRequest ProxyImportRequest containing information about the proxy to attach
    * @param item ArchiveEntry representing the item to attach it to
    * @param correctProxyBucket validated proxy bucket that it should come from
    * @return a Future, containing a Play result
    */
  private def performProxyImport(importRequest:ProxyImportRequest, item:ArchiveEntry, correctProxyBucket:String) = {
    implicit val s3client:AmazonS3 = s3ClientMgr.getS3Client(region = item.region)
    Future.fromTry(Try {
      s3client.doesObjectExist(correctProxyBucket, importRequest.proxyPath)
    }).flatMap({
      case false => Future(BadRequest(GenericErrorResponse("error", "that proxy does not exist").asJson))
      case true =>
        ProxyLocation.fromS3(
          correctProxyBucket,
          importRequest.proxyPath,
          item.bucket,
          item.path,
          Some(importRequest.proxyType)
        ).flatMap({
          case Left(err)=>
            Future(InternalServerError(GenericErrorResponse("error",s"Could not get proxy location from s3 $err").asJson))
          case Right(newRecord)=>
            Future.sequence(Seq(
              wrapSaveProxy(newRecord),
              ProxyLocator.setProxiedWithRetry(item.id)
            )).map(_=>Ok(GenericErrorResponse("ok","proxy set").asJson))
        })
    })
  }

  /**
    * Ensures that the requested proxy bucket does not conflict with the configured one and that there is not an existing
    * proxy in place (or we are allowed to over-write).  If both these conditions are met, calls performProxyImport to do the
    * actual import
    * @param importRequest ProxyImportRequest containing information about the proxy to attach
    * @param item ArchiveEntry representing the item to attach it to
    * @param correctProxyBucket validated proxy bucket that it should come from
    * @param existingProxies looked-up list of existing proxies for the given item
    * @return a Future, containing a Play response
    */
  def checkAndPerformProxyImport(importRequest:ProxyImportRequest, item:ArchiveEntry, correctProxyBucket:String, existingProxies:List[ProxyLocation]):Future[Result] = {
    if(importRequest.proxyBucket.isDefined && importRequest.proxyBucket.get != correctProxyBucket) {
      Future(Conflict(GenericErrorResponse("conflict","incorrect proxy bucket for item").asJson))
    } else {
      if(existingProxies.exists(_.proxyType == importRequest.proxyType) && !importRequest.overwrite.getOrElse(false)) {
        Future(Conflict(GenericErrorResponse("conflict",s"a proxy of type ${importRequest.proxyType} already exists").asJson))
      } else {
        performProxyImport(importRequest, item, correctProxyBucket)
      }
    }
  }

  /**
    * simplifies the Dynamo read response, by failing the Future if any errors are present
    * @param input original response from DynamoDB
    * @return a Future containing a list of ProxyLocation.  If any read fails, the whole Future fails.
    */
  private def simplifyDynamoReturn(input:Future[List[Either[DynamoReadError, ProxyLocation]]]):Future[List[ProxyLocation]] = input.flatMap(results=>{
    val failures = results.collect({case Left(err)=>err})
    if(failures.nonEmpty) {
      Future.failed(new RuntimeException(failures.map(_.toString).mkString(";")))
    } else {
      Future(results.collect({case Right(result)=>result}))
    }
  })

  def importProxy = IsAuthenticatedAsync(circe.json(2048)) { uid=> request=>
    request.body.as[ProxyImportRequest] match {
      case Left(err)=>
        Future(BadRequest(GenericErrorResponse("bad_request", err.toString()).asJson))
      case Right(importRequest)=>
        for {
          item <- indexer.getById(importRequest.itemId) //this version fails on a RuntimeException if there is no item present
          itemsScanTarget <- scanTargetDAO.targetForBucket(item.bucket)
          proxyBucket <- proxyBucketForMaybeScanTarget(itemsScanTarget)
          proxyRecords <- simplifyDynamoReturn(proxyLocationDAO.getAllProxiesFor(item.id))
          result <- checkAndPerformProxyImport(importRequest, item, proxyBucket, proxyRecords)
        } yield result
    }
  }
}
