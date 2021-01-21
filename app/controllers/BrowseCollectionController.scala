package controllers

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ListObjectsRequest, S3ObjectSummary}
import com.sksamuel.elastic4s.http.search.TermsAggResult
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ScanTarget, ScanTargetDAO}
import helpers.{InjectableRefresher, ItemFolderHelper}

import javax.inject.{Inject, Singleton}
import play.api.libs.circe.Circe
import play.api.{Configuration, Logger}
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{ErrorListResponse, GenericErrorResponse, ObjectListResponse, PathInfoResponse}
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.libs.ws.WSClient
import play.api.mvc.Result
import requests.SearchRequest

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

@Singleton
class BrowseCollectionController @Inject() (override val config:Configuration,
                                            s3ClientMgr:S3ClientManager,
                                            scanTargetDAO:ScanTargetDAO,
                                            override val controllerComponents: ControllerComponents,
                                            esClientMgr:ESClientManager,
                                            override val wsClient:WSClient,
                                            override val refresher:InjectableRefresher,
                                            folderHelper:ItemFolderHelper)
extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe{
  import com.sksamuel.elastic4s.http.ElasticDsl._

  private val logger=Logger(getClass)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val s3Client = s3ClientMgr.getClient(awsProfile)
  private val esClient = esClientMgr.getClient()

  private val indexName = config.get[String]("externalData.indexName")


  /**
    * execute the provided body with a looked-up ScanTarget.
    * automatically return an error if the ScanTarget cannot be found.
    * @param collectionName bucket to look up
    * @param block function that takes a ScanTarget instance and returns an HTTP result
    */
  def withScanTargetAsync(collectionName:String)(block: ScanTarget=>Future[Result]):Future[Result] = scanTargetDAO.targetForBucket(collectionName).flatMap({
    case Some(Left(err)) =>
      logger.error(s"Could not verify bucket name $collectionName: $err")
      Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
    case None =>
      logger.error(s"Bucket $collectionName is not managed by us")
      Future(BadRequest(GenericErrorResponse("not_registered", s"$collectionName is not a registered collection").asJson))
    case Some(Right(target)) =>
      block(target)
  })

  def withScanTarget(collectionName:String)(block: ScanTarget=>Result):Future[Result] =
    withScanTargetAsync(collectionName){ target=> Future(block(target)) }

  /**
    * iterate through the S3 bucket recursively, until there are no more prefixes available.
    * @param baseRequest ListObjectsRequest that contains details of what we want to list. This is updated to add pagination before actual sending to S3
    * @param s3Client AmazonS3 client object
    * @param continuationToken Optional continuation token. Defaults to None; for recursion purposes, don't specify when calling
    * @param currentSummaries List of current ObjectSummary. Defaults to empty sequence; for recursion purposes, don't specify when calling
    * @return list of folder names ("common prefixes") from the S3 bucket, as a List of strings
    */
  def recurseGetFolders(baseRequest:ListObjectsRequest, s3Client:AmazonS3,
                        continuationToken:Option[String]=None, currentSummaries:Seq[String]=Seq()):Seq[String] =
  {
    val finalRequest = continuationToken match {
      case None=>baseRequest
      case Some(token)=>baseRequest.withMarker(token)
    }

    val result = s3Client.listObjects(finalRequest)
    val updatedList = currentSummaries ++ result.getCommonPrefixes.asScala

    Option(result.getNextMarker) match {
      case Some(marker) =>
        recurseGetFolders(baseRequest, s3Client, Some(marker), updatedList)
      case None=>
        updatedList
    }
  }

  /**
    * get all of the "subfolders" ("common prefix" in s3 parlance) for the provided bucket, but only if it
    * is one that is registered as managed by us.
    * this is to drive the tree view in the browse window
    * @param collectionName s3 bucket to query
    * @param prefix parent folder to list. If none, then lists the root
    * @return
    */
  def getFolders(collectionName:String, prefix:Option[String]) = APIAuthAction.async {
//    withScanTarget(collectionName) { target=>
//      val rq = new ListObjectsRequest().withBucketName(collectionName).withDelimiter("/")
//      val finalRq = prefix match {
//        case Some(p)=>rq.withPrefix(p)
//        case None=>rq
//      }
//      try {
//        val localS3Client = s3ClientMgr.getS3Client(awsProfile, Some(target.region))
//        val result = recurseGetFolders(finalRq,localS3Client)
//        logger.debug(s"Got result:")
//        result.foreach(summ => logger.debug(s"\t$summ"))
//        Ok(ObjectListResponse("ok","folder",result, -1).asJson)
//      } catch {
//        case ex:Throwable=>
//          logger.error("Could not list S3 bucket: ", ex)
//          InternalServerError(GenericErrorResponse("error", ex.toString).asJson)
//      }
//    }
    folderHelper.scanFolders(indexName, collectionName, prefix, 1)
      .map(results=>{
        logger.info("getFolders got result: ")
        results.foreach(summ=>logger.info(s"\t$summ"))
        Ok(ObjectListResponse("ok","folder",results,-1).asJson)
      }).recover({
      case err:Throwable=>
        logger.error("Could not get prefixes from index: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  /**
    * converts a TermsAggResult from Elasticsearch into a simple map of bucket->docCount
    * @param result TermsAggResult from Elasticsearch
    * @return a Map of String->Long
    */
  def bucketsToCountMap(result:TermsAggResult) = {
    result.buckets.map(b=>Tuple2(b.key,b.docCount)).toMap
  }

  def pathSummary(collectionName:String, prefix:Option[String]) = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SearchRequest].fold(
      err=>
        Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      searchRequest=>
        withScanTargetAsync(collectionName) { target=>
          val queries = Seq(
            Some(matchQuery("bucket.keyword", collectionName)),
            prefix.map(pfx=>termQuery("path", pfx))
          ).collect({case Some(x)=>x}) ++ searchRequest.toSearchParams

          val aggs = Seq(
            sumAgg("totalSize","size"),
            termsAgg("deletedCounts","beenDeleted"),
            termsAgg("proxiedCounts","beenProxied"),
            termsAgg("typesCount", "mimeType.major.keyword")
          )

          esClient.execute(search(indexName) query boolQuery().must(queries) aggregations aggs).map({
            case Left(err)=>
              InternalServerError(GenericErrorResponse("search_error", err.toString).asJson)
            case Right(response)=>
              logger.info(s"Got ${response.result.aggregations}")

              Ok(PathInfoResponse("ok",
                response.result.hits.total,
                response.result.aggregations.sum("totalSize").value.toLong,
                bucketsToCountMap(response.result.aggregations.terms("deletedCounts")),
                bucketsToCountMap(response.result.aggregations.terms("proxiedCounts")),
                bucketsToCountMap(response.result.aggregations.terms("typesCount")),
              ).asJson)
          })
      })
  }

  def getCollections() = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case Some(Right(profile)) =>
        scanTargetDAO.allScanTargets().map(resultList => {
          val errors = resultList.collect({ case Left(err) => err })
          if (errors.nonEmpty) {
            InternalServerError(ErrorListResponse("db_error", "", errors.map(_.toString)).asJson)
          } else {
            val collectionsList = resultList.collect({case Right(target)=>target}).map(_.bucketName)
            val allowedCollections = if(profile.allCollectionsVisible){
              collectionsList
            } else {
              collectionsList.intersect(profile.visibleCollections)
            }
            Ok(ObjectListResponse("ok", "collection", allowedCollections.sorted, allowedCollections.length).asJson)
          }
        })
      case Some(Left(error))=>
        logger.error(s"Corrupted login profile? ${error.toString}")
        Future(InternalServerError(GenericErrorResponse("profile_error","Your login profile seems corrupted, try logging out and logging in again").asJson))
      case None=>
        logger.error(s"No user profile in session")
        Future(Forbidden(GenericErrorResponse("profile_error","You do not appear to be logged in").asJson))
    }
  }
}
