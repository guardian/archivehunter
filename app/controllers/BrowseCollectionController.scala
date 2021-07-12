package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import auth.{BearerTokenAuth, Security}
import com.sksamuel.elastic4s.http.search.TermsAggResult
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{PathCacheIndexer, ScanTarget, ScanTargetDAO}
import helpers.{ItemFolderHelper, WithScanTarget}

import javax.inject.{Inject, Singleton}
import play.api.libs.circe.Circe
import play.api.{Configuration, Logger}
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{ErrorListResponse, GenericErrorResponse, ObjectListResponse, PathInfoResponse}
import io.circe.syntax._
import io.circe.generic.auto._
import org.slf4j.LoggerFactory
import play.api.cache.SyncCacheApi
import requests.SearchRequest

import scala.annotation.switch
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class BrowseCollectionController @Inject() (override val config:Configuration,
                                            s3ClientMgr:S3ClientManager,
                                            scanTargetDAO:ScanTargetDAO,
                                            override val controllerComponents: ControllerComponents,
                                            esClientMgr:ESClientManager,
                                            override val bearerTokenAuth:BearerTokenAuth,
                                            folderHelper:ItemFolderHelper,
                                            override val cache:SyncCacheApi)
                                           (implicit actorSystem:ActorSystem, mat:Materializer)
extends AbstractController(controllerComponents) with Security with WithScanTarget with Circe{
  import com.sksamuel.elastic4s.http.ElasticDsl._

  private val logger=LoggerFactory.getLogger(getClass)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val s3Client = s3ClientMgr.getClient(awsProfile)
  private val esClient = esClientMgr.getClient()

  private val indexName = config.get[String]("externalData.indexName")
  private val pathCacheIndexer = new PathCacheIndexer(config.getOptional[String]("externalData.pathCacheIndex").getOrElse("pathcache"), esClient)

  /**
    * get all of the "subfolders" ("common prefix" in s3 parlance) for the provided bucket, but only if it
    * is one that is registered as managed by us.
    * this is to drive the tree view in the browse window
    * @param collectionName s3 bucket to query
    * @param prefix parent folder to list. If none, then lists the root
    * @return
    */
  def getFolders(collectionName:String, prefix:Option[String]) = IsAuthenticatedAsync { uid=> request=>
    val maybePrefix = prefix.flatMap(pfx=>{
      if(pfx=="") None else Some(pfx)
    })
    val maybePrefixPartsLength = maybePrefix.map(_.split("/").length)

    pathCacheIndexer.getPaths(collectionName, maybePrefix, maybePrefixPartsLength.getOrElse(0)+1)
      .map(results=>{
        logger.debug("getFolders got result: ")
        results.foreach(summ=>logger.debug(s"\t$summ"))
        Ok(ObjectListResponse("ok","folder",results.map(_.key),-1).asJson)
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

  def pathSummary(collectionName:String, prefix:Option[String]) = IsAuthenticatedAsync(circe.json(2048)) { uid=> request=>
    request.body.as[SearchRequest].fold(
      err=>
        Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      searchRequest=>
        withScanTargetAsync(collectionName, scanTargetDAO) { target=>
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

          esClient.execute(search(indexName) query boolQuery().must(queries) aggregations aggs).map(response=>{
            (response.status: @switch) match {
              case 200=>
                logger.info(s"Got ${response.result.aggregations}")

                Ok(PathInfoResponse("ok",
                  response.result.hits.total,
                  response.result.aggregations.sum("totalSize").value.toLong,
                  bucketsToCountMap(response.result.aggregations.terms("deletedCounts")),
                  bucketsToCountMap(response.result.aggregations.terms("proxiedCounts")),
                  bucketsToCountMap(response.result.aggregations.terms("typesCount")),
                ).asJson)
              case _=>
                InternalServerError(GenericErrorResponse("search_error", response.error.reason).asJson)
            }
          })
      })
  }

  def getCollections() = IsAuthenticatedAsync { uid=> request=>
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
