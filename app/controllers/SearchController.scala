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
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, StorageClassEncoder, ZonedDateTimeEncoder}
import play.api.libs.circe.Circe
import responses.{BasicSuggestionsResponse, GenericErrorResponse, ObjectListResponse}

import scala.concurrent.Future

@Singleton
class SearchController @Inject()(config:Configuration,cc:ControllerComponents,esClientManager:ESClientManager)
  extends AbstractController(cc) with ArchiveEntryHitReader with ZonedDateTimeEncoder with StorageClassEncoder with Circe {
  private val logger=Logger(getClass)
  val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("archivehunter")

  import com.sksamuel.elastic4s.http.ElasticDsl._

  def simpleStringSearch(q:Option[String],start:Option[Int],length:Option[Int]) = Action.async {
    val cli = esClientManager.getClient()

    val actualStart=start.getOrElse(0)
    val actualLength=length.getOrElse(50)

    q match {
      case Some(searchTerms) =>
        val responseFuture = cli.execute {
          search(indexName) query searchTerms from actualStart size actualLength sortBy fieldSort("path.keyword")
        }

        responseFuture.map({
          case Left(failure) =>
            InternalServerError(Json.obj("status" -> "error", "detail" -> failure.toString))
          case Right(results) =>
            val resultList = results.result.to[ArchiveEntry] //using the ArchiveEntryHitReader trait
            Ok(ObjectListResponse[IndexedSeq[ArchiveEntry]]("ok","entry",resultList,results.result.totalHits.toInt).asJson)
        })
      case None => Future(BadRequest(GenericErrorResponse("error", "you must specify a query string with ?q={string}").asJson))
    }
  }

  def suggestions = Action.async(parse.text) { request=>
    val cli = esClientManager.getClient()

    val sg = termSuggestion("sg").on("path").text(request.body)


    cli.execute({
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
    })
  }
}
