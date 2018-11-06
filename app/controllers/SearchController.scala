package controllers

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.mvc.{AbstractController, ControllerComponents}
import helpers.ESClientManager
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._
import com.sksamuel.elastic4s.circe._
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, StorageClassEncoder, ZonedDateTimeEncoder}
import play.api.libs.circe.Circe

@Singleton
class SearchController @Inject()(config:Configuration,cc:ControllerComponents,esClientManager:ESClientManager)
  extends AbstractController(cc) with ArchiveEntryHitReader with ZonedDateTimeEncoder with StorageClassEncoder with Circe {
  val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("ArchiveHunter")

  import com.sksamuel.elastic4s.http.ElasticDsl._

  def simpleStringSearch(searchTerms:String) = Action.async {
    val cli = esClientManager.getClient()

    val responseFuture = cli.execute {
      search(indexName) query searchTerms
    }

    responseFuture.map({
      case Left(failure)=>
        InternalServerError(Json.obj("status"->"error","detail"->failure.toString))
      case Right(results)=>
        val resultList = results.result.to[ArchiveEntry]  //using the ArchiveEntryHitReader trait
        Ok(resultList.asJson)
    })
  }
}
