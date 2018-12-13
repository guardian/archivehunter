package controllers

import com.amazonaws.services.s3.model.ListObjectsRequest
import com.sksamuel.elastic4s.http.search.TermsAggResult
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ScanTarget, ScanTargetDAO}
import helpers.InjectableRefresher
import javax.inject.Inject
import play.api.libs.circe.Circe
import play.api.{Configuration, Logger}
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{ErrorListResponse, GenericErrorResponse, ObjectListResponse, PathInfoResponse}
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.libs.ws.WSClient
import play.api.mvc.Result

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class BrowseCollectionController @Inject() (override val config:Configuration,
                                            s3ClientMgr:S3ClientManager,
                                            scanTargetDAO:ScanTargetDAO,
                                            override val controllerComponents: ControllerComponents,
                                            esClientMgr:ESClientManager,
                                            override val wsClient:WSClient,
                                            override val refresher:InjectableRefresher)
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
    * get all of the "subfolders" ("common prefix" in s3 parlance) for the provided bucket, but only if it
    * is one that is registered as managed by us.
    * this is to drive the tree view in the browse window
    * @param collectionName s3 bucket to query
    * @param prefix parent folder to list. If none, then lists the root
    * @return
    */
  def getFolders(collectionName:String, prefix:Option[String]) = APIAuthAction.async {
    withScanTarget(collectionName) { target=>
      val rq = new ListObjectsRequest().withBucketName(collectionName).withDelimiter("/")
      val finalRq = prefix match {
        case Some(p)=>rq.withPrefix(p)
        case None=>rq
      }
      try {
        val result = s3Client.listObjects(finalRq)
        logger.debug(s"Got result:")
        result.getObjectSummaries.asScala.foreach(summ => logger.debug(s"\t$summ"))
        Ok(ObjectListResponse("ok","folder",result.getCommonPrefixes.asScala, -1).asJson)
      } catch {
        case ex:Throwable=>
          logger.error("Could not list S3 bucket: ", ex)
          InternalServerError(GenericErrorResponse("error", ex.toString).asJson)
      }
    }
  }

  def bucketsToCountMap(result:TermsAggResult) = {
    result.buckets.map(b=>Tuple2(b.key,b.docCount)).toMap
  }

  def pathSummary(collectionName:String, prefix:Option[String]) = APIAuthAction.async {
    withScanTargetAsync(collectionName) { target=>
      val queries = Seq(
        Some(matchQuery("bucket.keyword", collectionName)),
        prefix.map(pfx=>termQuery("path", pfx))
      ).collect({case Some(x)=>x})

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
    }
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
              profile.visibleCollections
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
