package controllers

import akka.NotUsed

import java.util.UUID
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Framing, Keep, Sink, Source}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxBulkEntryDAO, LightboxEntryDAO, RestoreStatus, RestoreStatusEncoder}

import javax.inject.{Inject, Named, Singleton}
import models.{ArchiveEntryDownloadSynopsis, ServerTokenDAO, ServerTokenEntry}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents, ResponseHeader, Result}
import responses.{BulkDownloadInitiateResponse, GenericErrorResponse, ObjectGetResponse, RestoreStatusResponse}
import io.circe.generic.auto._
import io.circe.syntax._
import com.sksamuel.elastic4s.circe._
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, Indexer, StorageClass, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import helpers.S3Helper.getPresignedURL
import helpers.{LightboxHelper, SearchHitToArchiveEntryFlow}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}
import akka.pattern.ask
import akka.util.ByteString
import auth.{BearerTokenAuth, Security}
import com.sksamuel.elastic4s.streams.ScrollPublisher
import org.slf4j.LoggerFactory
import play.api.cache.SyncCacheApi
import play.api.http.HttpEntity
import requests.SearchRequest
import services.GlacierRestoreActor

import scala.concurrent.duration._

@Singleton
class BulkDownloadsController @Inject()(override val config:Configuration,
                                        override val cache:SyncCacheApi,
                                        serverTokenDAO: ServerTokenDAO,
                                        lightboxBulkEntryDAO: LightboxBulkEntryDAO,
                                        lightboxEntryDAO: LightboxEntryDAO,
                                        esClientManager: ESClientManager,
                                        s3ClientManager: S3ClientManager,
                                        cc:ControllerComponents,
                                        override val bearerTokenAuth:BearerTokenAuth,
                                        @Named("glacierRestoreActor") glacierRestoreActor:ActorRef,
                                       )(implicit system:ActorSystem)
  extends AbstractController(cc) with Security with Circe with ArchiveEntryHitReader with ZonedDateTimeEncoder with RestoreStatusEncoder {

  private val logger=LoggerFactory.getLogger(getClass)

  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

  val tokenLongDuration = config.getOptional[Int]("serverToken.longLivedDuration").getOrElse(7200)  //default value is 2 hours

  private val profileName = config.getOptional[String]("externalData.awsProfile")
  protected implicit val esClient = esClientManager.getClient()

  private val indexer = new Indexer(config.get[String]("externalData.indexName"))

  private implicit val mat:Materializer = ActorMaterializer.create(system)

  private def errorResponse(updatedToken:ServerTokenEntry) = serverTokenDAO
    .put(updatedToken)
    .map(saveResult=>{
      logger.error("generic error response for token")
      Forbidden(GenericErrorResponse("forbidden","invalid or expired token").asJson)
    }).recover({
    case err:Throwable=>
      logger.error(s"Could not update token: ", err)
      Forbidden(GenericErrorResponse("forbidden","invalid or expired token").asJson)
  })

  /**
    * bring back a list of all entries for the given bulk.
    * @param bulkEntry bulkEntry identifying the objects to query
    * @return a Future, containing a sequence of ArchiveEntryDownloadSynopsis
    */
  protected def entriesForBulk(bulkEntry:LightboxBulkEntry, maybeFrom:Option[Int]=None, maybeLimit:Option[Int]=None) = {
    val query = LightboxHelper.lightboxSearch(indexName, Some(bulkEntry.id), bulkEntry.userEmail) sortBy fieldSort("path.keyword") scroll "5m"

    val finalQuery = if(maybeFrom.isDefined && maybeLimit.isDefined) {
      query.from(maybeFrom.get).limit(maybeLimit.get)
    } else {
      query
    }

    val source = Source.fromPublisher(esClient.publisher(finalQuery))
    val hitConverter = new SearchHitToArchiveEntryFlow()
    val sink = Sink.fold[Seq[ArchiveEntryDownloadSynopsis], ArchiveEntryDownloadSynopsis](Seq())({ (acc,entry)=>
      acc ++ Seq(entry)
    })
    source
      .via(hitConverter)
      .map(entry=>ArchiveEntryDownloadSynopsis.fromArchiveEntry(entry))
      .toMat(sink)(Keep.right)
      .run()
  }

  protected def getSearchSource(p:ScrollPublisher):Source[ArchiveEntry,NotUsed] = {
    val source = Source.fromPublisher(p)
    val hitConverter = new SearchHitToArchiveEntryFlow()
    source
      .via(hitConverter)
  }

  /**
    * creates a stream that yields ArchiveentryDownloadSynopsis objects as an NDJSON stream
    * @param bulkEntry
    * @return
    */
  protected def streamingEntriesForBulk(bulkEntry:LightboxBulkEntry) = {
    val query = LightboxHelper.lightboxSearch(indexName, Some(bulkEntry.id), bulkEntry.userEmail) sortBy fieldSort("path.keyword") scroll "5m"

    getSearchSource(esClient.publisher(query))
      .map(entry=>ArchiveEntryDownloadSynopsis.fromArchiveEntry(entry))
      .map(_.asJson)
      .map(_.noSpaces + "\n")
      .map(ByteString.apply)
  }

  protected def saveTokenOnly(updatedToken:ServerTokenEntry, bulkEntry: LightboxBulkEntry) = serverTokenDAO
    .put(updatedToken)
    .flatMap(_=>{
      val retrievalToken = ServerTokenEntry.create(duration = tokenLongDuration, forUser = updatedToken.createdForUser, associatedId = updatedToken.associatedId) //create a 2 hour token to cover the download.
      serverTokenDAO.put(retrievalToken).map({
        case None =>
          Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, None).asJson)
        case Some(Right(_)) =>
          Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, None).asJson)
        case Some(Left(err)) =>
          logger.error(s"Could not save retrieval token: $err")
          InternalServerError(GenericErrorResponse("db_error", s"Could not save retrieval token: $err").asJson)
      })
    }).recoverWith({
      case err:Throwable=>
        logger.error(s"Could not search index for bulk entries: $err")
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
    })

  protected def saveTokenAndGetDownload(updatedToken:ServerTokenEntry, bulkEntry: LightboxBulkEntry) = serverTokenDAO
    .put(updatedToken)
    .flatMap(_=>{
      entriesForBulk(bulkEntry).flatMap(results=> {
        val retrievalToken = ServerTokenEntry.create(duration = tokenLongDuration, forUser = updatedToken.createdForUser) //create a 2 hour token to cover the download.
        serverTokenDAO.put(retrievalToken).map({
          case None =>
            Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, Some(results)).asJson)
          case Some(Right(_)) =>
            Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, Some(results)).asJson)
          case Some(Left(err)) =>
            logger.error(s"Could not save retrieval token: $err")
            InternalServerError(GenericErrorResponse("db_error", s"Could not save retrieval token: $err").asJson)
        })
      }).recoverWith({
        case err:Throwable=>
          logger.error(s"Could not search index for bulk entries: $err")
          Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      })
    })

  /**
    * take a one-time code generated in LightboxController, validate and expire it, then return the information of the
    * associated LightboxBulkEntry
    * @param codeValue value of the code
    * @return a Json response containing the metadata of the
    */
  def initiateWithOnetimeCode(codeValue:String) = Action.async {
    initiateGuts(codeValue)(saveTokenAndGetDownload)
  }

  /**
    * take a one-time code generated in LightboxController, validate and expire it, and return a long-term code.
    * does NOT interrogate the LightboxBulkEntry, in v2 this is done seperately as it has a tendency to time out
    * for larger restores
    * @param codeValue value of the code
    * @return a Json response containing the metadata of the
    */
  def initiateWithOnetimeCodeV2(codeValue:String) = Action.async {
    initiateGuts(codeValue)(saveTokenOnly)
  }

  /**
    * get the bulk download summary for v2, as an NDJSON stream
    * @param tokenValue long-term token to retrieve content
    * @return
    */
  def bulkDownloadSummary(tokenValue:String) = Action.async {
    val tokenFut = serverTokenDAO.get(tokenValue)
    tokenFut.flatMap({
      case None =>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Left(err)) =>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Right(token)) =>
        token.associatedId match {
          case Some(associatedId) =>
            lightboxBulkEntryDAO.entryForId(UUID.fromString(associatedId)).map({
              case Some(Right(bulkEntry)) =>
                val streamingSource = streamingEntriesForBulk(bulkEntry)
                Result(
                  header = ResponseHeader(200, Map.empty),
                  body = HttpEntity.Streamed(streamingSource, None, Some("application/ndjson"))
                )
              case _ =>
                logger.error(s"Could not retrieve lightbox bulk associated with ${token.associatedId.get}")
                InternalServerError(GenericErrorResponse("db_error", "could not retrieve lightbox bulk").asJson)
            })
          case None =>
            Future(NotFound(GenericErrorResponse("not_found", "token does not identify bulk").asJson))
        }
    })
  }

  /**
    * common code for v1 and v2 initiate
    * @param codeValue
    * @param cb
    * @return
    */
  def initiateGuts(codeValue:String)(cb:(ServerTokenEntry, LightboxBulkEntry)=>Future[Result]) = {
    serverTokenDAO.get(codeValue).flatMap({
      case None=>
        logger.error(s"No token exists for $codeValue")
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Left(err))=>
        logger.error(s"Could not verify one-time token: ${err.toString}")
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Right(token))=>
        val updatedToken = token.updateCheckExpired(maxUses=Some(1)).copy(uses = token.uses+1)
        if(updatedToken.expired){
          logger.error(s"Token ${updatedToken.value} is expired, denying access")
          errorResponse(updatedToken)
        } else {
          token.associatedId match {
            case None=>
              logger.error(s"Token ${token.value} has no bulk associated with it!")
              errorResponse(updatedToken)
            case Some(associatedId)=>
              if(associatedId=="loose"){
                val looseBulkEntry = LightboxBulkEntry.forLoose(updatedToken.createdForUser.getOrElse("unknown"),-1)
                cb(updatedToken, looseBulkEntry)
              } else {
                lightboxBulkEntryDAO.entryForId(UUID.fromString(associatedId)).flatMap({
                  case Some(Left(err)) => errorResponse(updatedToken)
                  case None => errorResponse(updatedToken)
                  case Some(Right(bulkEntry)) =>
                    cb(updatedToken, bulkEntry)
                })
              }
          }
        }
    })
  }

  def getDownloadIdWithToken(tokenValue:String, fileId:String) = Action.async {
    implicit val timeout:akka.util.Timeout = 30 seconds

    serverTokenDAO.get(tokenValue).flatMap({
      case None=>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Left(err))=>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Right(_))=>
        indexer.getById(fileId).flatMap(archiveEntry=>{
          val s3Client = s3ClientManager.getS3Client(profileName,archiveEntry.region)
          val response = (glacierRestoreActor ? GlacierRestoreActor.CheckRestoreStatusBasic(archiveEntry)).mapTo[GlacierRestoreActor.GRMsg]

          response.map({
            case GlacierRestoreActor.NotInArchive(entry)=>
              getPresignedURL(archiveEntry)(s3Client)
                .map(url=>Ok(RestoreStatusResponse("ok",entry.id, RestoreStatus.RS_UNNEEDED, None, Some(url.toString)).asJson))
            case GlacierRestoreActor.RestoreCompleted(entry, expiry)=>
              getPresignedURL(archiveEntry)(s3Client)
                .map(url=>Ok(RestoreStatusResponse("ok", entry.id, RestoreStatus.RS_SUCCESS, Some(expiry), Some(url.toString)).asJson))
            case GlacierRestoreActor.RestoreInProgress(entry)=>
              Success(Ok(RestoreStatusResponse("ok", entry.id, RestoreStatus.RS_UNDERWAY, None, None).asJson))
            case GlacierRestoreActor.RestoreNotRequested(entry)=>
              if(entry.storageClass!=StorageClass.GLACIER){ //if the file is not registered as in Glacier, then update it.
                val updatedEntry = entry.copy(storageClass=StorageClass.GLACIER)
                indexer.indexSingleItem(updatedEntry, Some(updatedEntry.id)).onComplete({
                  case Failure(err)=>
                    logger.error(s"Could not update storage class for incorrect item $entry: $err")
                  case Success(_)=>
                    logger.info(s"Updated $entry as it had invalid storage class. Now requesting restore")
                    glacierRestoreActor ! GlacierRestoreActor.InitiateRestoreBasic(updatedEntry,None)
                })
              }
              Success(Ok(RestoreStatusResponse("not_requested", entry.id, RestoreStatus.RS_ERROR, None, None).asJson))
            case GlacierRestoreActor.ItemLost(entry)=>
              logger.error(s"Bulk item ${entry.bucket}:${entry.path} is lost")
              val updatedEntry = entry.copy(beenDeleted = true)
              indexer.indexSingleItem(updatedEntry, Some(updatedEntry.id)).onComplete({
                case Success(_)=>
                  logger.info(s"${entry.bucket}:${entry.path} has been updated to indicate it is lost")
                case Failure(err)=>
                  logger.error(s"Could not update ${entry.bucket}:${entry.path}")
              })
              Success(NotFound(GenericErrorResponse("not_found","item has been deleted!").asJson))
            case GlacierRestoreActor.RestoreFailure(err)=>
              logger.error(s"Could not check restore status: ", err)
              Success(InternalServerError(GenericErrorResponse("error",err.toString).asJson))
          }).map({
            case Success(httpResponse)=>httpResponse
            case Failure(err)=>
              logger.error(s"Could not get download for $fileId with token $tokenValue", err)
              InternalServerError(GenericErrorResponse("error",err.toString).asJson)
          })
        }).recover({
          case err:Throwable=>
            logger.error(s"Could not ascertain glacier restore status for $fileId: ", err)
            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
        })
    })
  }
}
