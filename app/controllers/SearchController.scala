package controllers

import auth.{BearerTokenAuth, Security}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.mvc.{AbstractController, ControllerComponents, Request}
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.http.search.Aggregations
import com.sksamuel.elastic4s.searches.sort.SortOrder
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, StorageClassEncoder, ZonedDateTimeEncoder}
import helpers.LightboxHelper
import models.{ChartFacet, ChartFacetData, UserProfileDAO}
import org.slf4j.LoggerFactory
import play.api.cache.SyncCacheApi
import play.api.libs.circe.Circe
import requests.SearchRequest
import play.api.libs.ws.WSClient
import responses._

import scala.concurrent.Future

@Singleton
class SearchController @Inject()(override val config:Configuration,
                                 override val controllerComponents:ControllerComponents,
                                 esClientManager:ESClientManager,
                                 override val bearerTokenAuth: BearerTokenAuth,
                                 override val cache:SyncCacheApi)
                                (implicit val userProfileDAO:UserProfileDAO)
  extends AbstractController(controllerComponents) with Security with ArchiveEntryHitReader with ZonedDateTimeEncoder with StorageClassEncoder with Circe {

  override protected val logger=LoggerFactory.getLogger(getClass)
  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

  private val esClient = esClientManager.getClient()
  import com.sksamuel.elastic4s.http.ElasticDsl._

  def getEntry(fileId:String) = IsAuthenticatedAsync { _=> _=>
    esClient.execute {
      search(indexName) query termQuery("id",fileId)
    }.map(response=>{
      if(response.isError) {
        logger.error(s"Could not look up file ID $fileId: ${response.status} ${response.error.reason}")
        InternalServerError(GenericErrorResponse("db_error", response.error.reason).asJson)
      } else {
        val resultList = response.result.to[ArchiveEntry]
        resultList.headOption match {
          case Some(entry) =>
            Ok(ObjectGetResponse("ok", "entry", entry).asJson)
          case None =>
            NotFound(GenericErrorResponse("not_found", fileId).asJson)
        }
      }
    }).recover({
      case err:Throwable=>
        logger.error("Could not get entry: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def simpleStringSearch(q:Option[String],start:Option[Int],length:Option[Int]) = IsAuthenticatedAsync { _=> _=>
    val cli = esClientManager.getClient()

    val actualStart=start.getOrElse(0)
    val actualLength=length.getOrElse(50)

    q match {
      case Some(searchTerms) =>
        val responseFuture = esClient.execute {
          search(indexName) query { boolQuery.must(searchTerms, not(regexQuery("path.keyword",".*/+\\.[^\\.]+"))) } from actualStart size actualLength sortBy fieldSort("path.keyword")
        }

        responseFuture.map(response=>{
          if(response.isError) {
            InternalServerError(Json.obj("status" -> "error", "detail" -> response.error.reason))
          } else {
            val resultList = response.result.to[ArchiveEntry] //using the ArchiveEntryHitReader trait
            Ok(ObjectListResponse[IndexedSeq[ArchiveEntry]]("ok","entry",resultList,response.result.totalHits.toInt).asJson)
          }
        }).recover({
          case err:Throwable=>
            logger.error("Could not do browse search: ", err)
            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
        })
      case None => Future(BadRequest(GenericErrorResponse("error", "you must specify a query string with ?q={string}").asJson))
    }
  }

  def suggestions = IsAuthenticatedAsync(parse.text) { _=> request=>
    val sg = termSuggestion("sg").on("path").text(request.body)

    esClient.execute({
      search(indexName) suggestions {
        sg
      }
    }).map(response=>{
      if(response.isError) {
        InternalServerError(GenericErrorResponse("search failure", response.error.reason).asJson)
      } else {
        Ok(BasicSuggestionsResponse.fromEsResponse(response.result.termSuggestion("sg")).asJson)
      }
    }).recover({
      case err:Throwable=>
        logger.error("Could not do suggestions search: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def browserSearch(startAt:Int,pageSize:Int) = IsAuthenticatedAsync(circe.json(2048)) { _=> request=>
    request.body.as[SearchRequest].fold(
      error=>{
        Future(BadRequest(GenericErrorResponse("bad_request", error.toString).asJson))
      },
      request=> {
        esClient.execute {
          search(indexName) query {
            boolQuery().must(request.toSearchParams)
          } from startAt size pageSize sortBy fieldSort(request.toSortParam).order(request.toSortOrder)
        }.map(response=>{
          if(response.isError) {
            logger.error(s"Could not perform advanced search: ${response.status} ${response.error.reason}")
            InternalServerError(GenericErrorResponse("search_error", response.error.reason).asJson)
          } else {
            Ok(ObjectListResponse("ok", "entry", response.result.to[ArchiveEntry], response.result.totalHits.toInt).asJson)
          }
        })
      }
    ).recover({
      case err:Throwable=>
        logger.error("Could not do browse search: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def lightboxSearch(startAt:Int, pageSize:Int, bulkId:Option[String], user:String) = IsAuthenticatedAsync { _=> request=>
    targetUserProfile(request,user).flatMap({
      case None => Future(BadRequest(GenericErrorResponse("session_error", "no session present").asJson))
      case Some(Left(err)) =>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error", "session is corrupted, log out and log in again").asJson))
      case Some(Right(profile)) =>
        esClient.execute {
          LightboxHelper.lightboxSearch(indexName, bulkId, profile.userEmail) from startAt size pageSize sortBy fieldSort("path.keyword")
        }.map(response=>{
          if(response.isError) {
            logger.error(s"Could not perform lightbox query: ${response.status} ${response.error.reason}")
            InternalServerError(GenericErrorResponse("search_error", response.error.reason).asJson)
          } else {
            Ok(ObjectListResponse("ok", "entry", response.result.to[ArchiveEntry], response.result.totalHits.toInt).asJson)
          }
        }).recover({
          case err: Throwable =>
            logger.error("Could not do browse search: ", err)
            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
        })
    })
  }

  def chartSubBucketsFor[T](data:Map[String,Any]):Either[String, Map[String,T]] = {
    if(data.contains("buckets")){
      Right(data("buckets").asInstanceOf[List[Map[String, Any]]].map(entry=>{
        (entry.getOrElse("key_as_string", entry("key")).asInstanceOf[String], entry("doc_count").asInstanceOf[T])
      }).toMap)
    } else Left("Facet did not have buckets parameter")
  }

  def chartIntermediateRepresentation[T:io.circe.Encoder](aggs:Map[String,Any], forKey:String):Either[String, ChartFacet[T]] = {
    if(aggs.contains("buckets")){
      val buckets = aggs("buckets").asInstanceOf[List[Map[String,Any]]]
      val facetData = buckets.map(entry=>{
        if(entry.contains(forKey)){
          Some(chartSubBucketsFor(entry(forKey).asInstanceOf[Map[String, Any]]).map(data=>
            ChartFacetData[T](entry.getOrElse("key_as_string",entry("key")).asInstanceOf[String], data)
          ))
        } else None
      })
      logger.debug(facetData.toString)
      Right(ChartFacet(forKey,facetData.collect({case Some(Right(d))=>d})))
    } else Left("Facet did not have buckets parameter")
  }

  def getProxyFacets() = IsAuthenticatedAsync { _=> _=>
    esClient.execute {
      search(indexName) aggregations
        termsAggregation("Collection")
          .field("bucket.keyword")
          .subAggregations(
            termsAgg("hasProxy","proxied"),
            termsAgg("mediaType", "mimeType.major.keyword")
          )
    }.map(response=>{
      if(response.isError) {
        InternalServerError(GenericErrorResponse("search failure", response.error.reason).asJson)
      } else {
        val intermediateContent = Seq(
          chartIntermediateRepresentation[Int](response.result.aggregations.data("Collection").asInstanceOf[Map[String, Any]], "hasProxy").map(_.inverted()),
          chartIntermediateRepresentation[Int](response.result.aggregations.data("Collection").asInstanceOf[Map[String, Any]], "mediaType").map(_.inverted())
        )

        val finalContent = intermediateContent.map(_.map(entry => ChartDataResponse.fromIntermediateRepresentation(Seq(entry))))
        val errors = finalContent.collect({ case Left(err) => err })

        if (errors.nonEmpty) {
          InternalServerError(ErrorListResponse("render_error", "could not process aggregations data", errors.toList).asJson)
        } else {
          Ok(ChartDataListResponse("ok", finalContent.collect({ case Right(data) => data }).flatten).asJson)
        }
      }
    })
  }

  /**
    * endpoint to return data about a specific file, given the collection name and the file path. Both must exactly
    * match in order to be returned
    * @param collectionName
    * @param filePath
    * @return
    */
  def getByFilename(collectionName:String,filePath:String) = IsAuthenticatedAsync { _=> _=>
    esClient.execute {
      search(indexName) query boolQuery().withMust(Seq(
        matchQuery("bucket.keyword",collectionName),
        matchQuery("path.keyword", filePath)
      ))
    }.map(response=>{
      if(response.isError) {
        InternalServerError(GenericErrorResponse("search failure", response.error.reason).asJson)
      } else {
        Ok(ObjectGetResponse("ok", "archiveentry", response.result.to[ArchiveEntry]).asJson)
      }
    })
  }

  /**
    * endpoint to search for an exact match on the given filepath, in any collection
    * @param filePath
    * @return
    */
  def searchByFilename(filePath:String) = IsAuthenticatedAsync { _=> _=>
    esClient.execute {
      search(indexName) query matchQuery("path.keyword", filePath)
    }.map(response=>{
      if(response.isError) {
        InternalServerError(GenericErrorResponse("search failure", response.error.reason).asJson)
      } else {
          if (response.result.hits.total > 0) {
            Ok(ObjectListResponse("ok", "archiveentry", response.result.to[ArchiveEntry], response.result.hits.total.toInt).asJson)
          } else {
            NotFound(GenericErrorResponse("not_found", s"Nothing found at path $filePath").asJson)
          }
      }
    })
  }
}
