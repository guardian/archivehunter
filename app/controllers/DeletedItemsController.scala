package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.util.ByteString
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, Indexer, StorageClassEncoder, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ItemNotFound, ScanTarget, ScanTargetDAO}
import helpers.{InjectableRefresher, ItemFolderHelper, WithScanTarget}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents, ResponseHeader, Result}
import responses.{DeletionSummaryResponse, GenericErrorResponse, ObjectListResponse, PathInfoResponse}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.http.HttpEntity
import requests.SearchRequest

import scala.annotation.switch
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class DeletedItemsController @Inject() (override val config:Configuration,
                                            scanTargetDAO:ScanTargetDAO,
                                            override val controllerComponents: ControllerComponents,
                                            esClientMgr:ESClientManager,
                                            override val wsClient:WSClient,
                                            override val refresher:InjectableRefresher)
                                           (implicit actorSystem:ActorSystem, mat:Materializer)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with WithScanTarget with ArchiveEntryHitReader
    with ZonedDateTimeEncoder with StorageClassEncoder with Circe with AdminsOnly
{
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  private implicit val esClient = esClientMgr.getClient()
  private val logger=Logger(getClass)

  private val indexName = config.get[String]("externalData.indexName")

  private val indexer = new Indexer(indexName)

  protected def makeQuery(collectionName:String, prefix:Option[String], searchRequest: SearchRequest) = {
    val queries = Seq(
      Some(matchQuery("bucket.keyword", collectionName)),
      prefix.map(pfx => termQuery("path", pfx)),
      Some(matchQuery("beenDeleted", true))
    ).collect({ case Some(x) => x }) ++ searchRequest.toSearchParams

    val aggs = Seq(
      sumAgg("totalSize", "size"),
    )

    search(indexName) query boolQuery().must(queries) aggregations aggs
  }

  /**
    * returns a summary of how many items have been deleted for the given collection.
    * @param collectionName
    * @param prefix
    * @return
    */
  def deletedItemsSummary(collectionName:String, prefix:Option[String]) = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SearchRequest].fold(
      err=>
        Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      searchRequest=> {
        withScanTargetAsync(collectionName, scanTargetDAO) { target =>
//          val correctedPrefix = prefix match {
//            case None=>None
//            case Some(pfx)=>if(pfx.endsWith("/")) pfx.substring(0, pfx.length-2) else pfx
//          }
          esClient.execute(makeQuery(collectionName, prefix, searchRequest)).map(response => {
            (response.status: @switch) match {
              case 200 =>
                logger.info(s"Got ${response.result.aggregations}")

                Ok(DeletionSummaryResponse("ok",
                  response.result.hits.total,
                  response.result.aggregations.sum("totalSize").value.toLong
                ).asJson)
              case _ =>
                InternalServerError(GenericErrorResponse("search_error", response.error.reason).asJson)
            }
          }).recover({
            case err: Throwable =>
              logger.error(s"Could not scan $collectionName (with prefix $prefix) for deleted items: ${err.getMessage}", err)
              InternalServerError(GenericErrorResponse("db_error", err.getMessage).asJson)
          })
        }
      })
  }

  /**
    * sends an NDJSON stream of items marked as deleted from the given collection and prefix
    * @param collectionName collection name to scan
    * @param prefix optional path prefix
    * @return
    */
  def deletedItemsListStreaming(collectionName:String, prefix:Option[String], limit:Option[Long]) = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SearchRequest].fold(
      err=>
        Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      searchRequest=> {
        withScanTarget(collectionName, scanTargetDAO) { _ =>
          val query = makeQuery(collectionName, prefix, searchRequest).scroll("2m")
          logger.debug(query.toString)
          val source = Source.fromPublisher(esClient.publisher(query))
          val appliedLimit = limit.getOrElse(1000L)

          val contentStream = source
            .limit(appliedLimit)
            .map(_.to[ArchiveEntry])
            .map(_.asJson)
            .map(_.noSpaces + "\n")
            .map(ByteString.apply)
          Result(
            header = ResponseHeader(status=200),
            body = HttpEntity.Streamed(contentStream,contentType=Some("application/x-ndjson"), contentLength=None)
          )
        }
      })
  }

  private def performItemDelete(collectionName:String, itemId:String) = esClient
    .execute(deleteById(indexName, "entry", itemId))
    .map(response=>{
      if(response.status==200 || response.status==201) {
        Ok(GenericErrorResponse("ok","item deleted").asJson)
      } else {
        logger.error(s"Could not delete item $itemId from $collectionName: ${response.status} ${response.error.reason}")
        InternalServerError(GenericErrorResponse("db_error","index returned error").asJson)
      }
    })
    .recover({
      case err:Throwable=>
        logger.error(s"Item delete request failed: ${err.getMessage}", err);
        InternalServerError(GenericErrorResponse("internal_error", err.getMessage).asJson)
    })

  def removeTombstoneById(collectionName:String, itemId:String) = APIAuthAction.async { request=>
    adminsOnlyAsync(request, true) {
      indexer.getByIdFull(itemId).flatMap({
        case Left(ItemNotFound(itemId))=>
          Future(NotFound(GenericErrorResponse("not_found", itemId).asJson))
        case Left(other)=>
          logger.error(s"Could not look up item $itemId from $collectionName: ${other.errorDesc}")
          Future(InternalServerError(GenericErrorResponse("error", other.errorDesc).asJson))
        case Right(entry)=>
          if(entry.bucket!=collectionName) {
            logger.warn(s"Invalid tombstone removal request: $itemId exist but is not within bucket $collectionName, returning 404")
            Future(NotFound(GenericErrorResponse("not_found", itemId).asJson))
          } else if(!entry.beenDeleted) {
            logger.warn(s"Invalid tombstone removal request: $itemId is not a tombstone")
            Future(Conflict(GenericErrorResponse("conflict","this item is not a tombstone").asJson))
          } else {
            performItemDelete(collectionName, itemId)
          }
      })

    }
  }
}
