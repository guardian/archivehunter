package controllers

import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.mvc.{AbstractController, ControllerComponents}
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.searches.sort.SortOrder
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, StorageClassEncoder, ZonedDateTimeEncoder}
import helpers.InjectableRefresher
import play.api.libs.circe.Circe
import requests.SearchRequest
import play.api.libs.ws.WSClient
import responses._

import scala.concurrent.Future

@Singleton
class SearchController @Inject()(override val config:Configuration,
                                 override val controllerComponents:ControllerComponents,
                                 esClientManager:ESClientManager,
                                 override val wsClient:WSClient,
                                 override val refresher:InjectableRefresher)
  extends AbstractController(controllerComponents) with ArchiveEntryHitReader with ZonedDateTimeEncoder with StorageClassEncoder with Circe
with PanDomainAuthActions {

  private val logger=Logger(getClass)
  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

  private val esClient = esClientManager.getClient()
  import com.sksamuel.elastic4s.http.ElasticDsl._

  def getEntry(fileId:String) = APIAuthAction.async {
    esClient.execute {
      search(indexName) query termQuery("id",fileId)
    }.map({
      case Left(failure)=>
        logger.error(s"Could not look up file ID $fileId: ${failure.toString}")
        InternalServerError(GenericErrorResponse("db_error", failure.toString).asJson)
      case Right(response)=>
        val resultList = response.result.to[ArchiveEntry]
        resultList.headOption match {
          case Some(entry) =>
            Ok(ObjectGetResponse("ok", "entry", entry).asJson)
          case None =>
            NotFound(GenericErrorResponse("not_found", fileId).asJson)
        }
    }).recover({
      case err:Throwable=>
        logger.error("Could not get entry: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def simpleStringSearch(q:Option[String],start:Option[Int],length:Option[Int]) = APIAuthAction.async {
    val cli = esClientManager.getClient()

    val actualStart=start.getOrElse(0)
    val actualLength=length.getOrElse(50)

    q match {
      case Some(searchTerms) =>
        val responseFuture = esClient.execute {
          search(indexName) query { boolQuery.must(searchTerms, not(regexQuery(("path.keyword",".*/+\\.[^\\.]+")))) } from actualStart size actualLength sortBy fieldSort("path.keyword")
        }

        responseFuture.map({
          case Left(failure) =>
            InternalServerError(Json.obj("status" -> "error", "detail" -> failure.toString))
          case Right(results) =>
            val resultList = results.result.to[ArchiveEntry] //using the ArchiveEntryHitReader trait
            Ok(ObjectListResponse[IndexedSeq[ArchiveEntry]]("ok","entry",resultList,results.result.totalHits.toInt).asJson)
        }).recover({
          case err:Throwable=>
            logger.error("Could not do browse search: ", err)
            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
        })
      case None => Future(BadRequest(GenericErrorResponse("error", "you must specify a query string with ?q={string}").asJson))
    }
  }

  def suggestions = APIAuthAction.async(parse.text) { request=>
    val sg = termSuggestion("sg").on("path").text(request.body)

    esClient.execute({
      search(indexName) suggestions {
        sg
      }
    }).map({
      case Left(failure)=>
        InternalServerError(GenericErrorResponse("search failure", failure.toString).asJson)
      case Right(results)=>
        logger.info("Got ES response:")
        logger.info(results.body.getOrElse("[empty body]"))
        Ok(BasicSuggestionsResponse.fromEsResponse(results.result.termSuggestion("sg")).asJson)
    }).recover({
      case err:Throwable=>
        logger.error("Could not do suggestions search: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def browserSearch(startAt:Int,pageSize:Int) = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SearchRequest].fold(
      error=>{
        Future(BadRequest(GenericErrorResponse("bad_request", error.toString).asJson))
      },
      request=> {
        logger.info(s"search params are ${request.toSearchParams}")
        esClient.execute {
          search(indexName) query {
            boolQuery().must(request.toSearchParams)
          } from startAt size pageSize sortBy fieldSort(request.toSortParam).order(request.toSortOrder)
        }.map({
          case Left(err) =>
            logger.error(s"Could not perform advanced search: $err")
            InternalServerError(GenericErrorResponse("search_error", err.toString).asJson)
          case Right(results) =>
            Ok(ObjectListResponse("ok", "entry", results.result.to[ArchiveEntry], results.result.totalHits.toInt).asJson)
        })
      }
    ).recover({
      case err:Throwable=>
        logger.error("Could not do browse search: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def lightboxSearch(startAt:Int, pageSize:Int, bulkId:Option[String]) = APIAuthAction.async {request=>
    val queryTerms = Seq(
      Some(matchQuery("lightboxEntries.owner", request.user.email)),
      bulkId.map(actualBulkId=>matchQuery("lightboxEntries.memberOfBulk", actualBulkId))
    ).collect({case Some(term)=>term})

    esClient.execute {
      search(indexName) query {
        nestedQuery(path="lightboxEntries", query = {
          boolQuery().must(queryTerms)
        })
      } from startAt size pageSize sortBy fieldSort("path.keyword")
    }.map({
      case Left(err)=>
        logger.error(s"Could not perform lightbox query: $err")
        InternalServerError(GenericErrorResponse("search_error", err.toString).asJson)
      case Right(results)=>
        results.result.to[ArchiveEntry].foreach(x=>println(x.toString))
        Ok(ObjectListResponse("ok","entry", results.result.to[ArchiveEntry], results.result.totalHits.toInt).asJson)
    }).recover({
      case err:Throwable=>
        logger.error("Could not do browse search: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def getProxyFacets() = APIAuthAction.async {
    esClient.execute {
      search(indexName) aggregations (
        termsAgg("hasProxy","proxied"),
        termsAgg("mediaType", "mimeType.major.keyword")
      )
    }.map({
      case Left(failure)=>
        InternalServerError(GenericErrorResponse("search failure", failure.toString).asJson)
      case Right(results)=>
        logger.info("Got ES response:")
        logger.info(results.body.getOrElse("[empty body]"))
        logger.info(results.result.aggregations.toString)

        val finalContent = Seq(
          ChartDataResponse.fromAggregatesMap[Int](results.result.aggregations.data("mediaType").asInstanceOf[Map[String, Any]], "Media Type"),
          ChartDataResponse.fromAggregatesMap[Int](results.result.aggregations.data("hasProxy").asInstanceOf[Map[String, Any]], "Has Proxy", totalForRemainder = Some(results.result.totalHits.toInt))
        )

        val errors = finalContent.collect({case Left(err)=>err})

        if(errors.nonEmpty){
          InternalServerError(ErrorListResponse("render_error","could not process aggregations data", errors.toList).asJson)
        } else {
          Ok(ChartDataListResponse("ok",finalContent.collect({case Right(data)=>data})).asJson)
        }
    })
  }
}
