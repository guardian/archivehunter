package controllers

import java.util.UUID
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxBulkEntryDAO, LightboxEntryDAO, RestoreStatus, RestoreStatusEncoder}

import javax.inject.{Inject, Named, Singleton}
import models.{ArchiveEntryDownloadSynopsis, ServerTokenDAO, ServerTokenEntry}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents, Result}
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
import services.GlacierRestoreActor

import scala.concurrent.duration._

@Singleton
class BulkDownloadsController @Inject()(config:Configuration,serverTokenDAO: ServerTokenDAO,
                                        lightboxBulkEntryDAO: LightboxBulkEntryDAO,
                                        lightboxEntryDAO: LightboxEntryDAO,
                                        esClientManager: ESClientManager,
                                        s3ClientManager: S3ClientManager,
                                        cc:ControllerComponents,
                                        @Named("glacierRestoreActor") glacierRestoreActor:ActorRef,
                                       )(implicit system:ActorSystem)
  extends AbstractController(cc) with Circe with ArchiveEntryHitReader with ZonedDateTimeEncoder with RestoreStatusEncoder {

  private val logger = Logger(getClass)

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

  protected def saveTokenOnly(updatedToken:ServerTokenEntry, bulkEntry: LightboxBulkEntry) = serverTokenDAO
    .put(updatedToken)
    .flatMap(_=>{
      val retrievalToken = ServerTokenEntry.create(duration = tokenLongDuration, forUser = updatedToken.createdForUser) //create a 2 hour token to cover the download.
      serverTokenDAO.put(retrievalToken).map({
        case None =>
          Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, Array()).asJson)
        case Some(Right(_)) =>
          Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, Array()).asJson)
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
            Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, results).asJson)
          case Some(Right(_)) =>
            Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, results).asJson)
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
    * get the bulk download summary for v2, possibly in pages
    * @param tokenValue
    * @param from
    * @param limit
    * @return
    */
  def bulkDownloadSummary(tokenValue:String, from:Option[Int], limit:Option[Int]) = Action.async {
    serverTokenDAO.get(tokenValue).flatMap({
      case None =>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Left(err)) =>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Right(token)) =>
        lightboxBulkEntryDAO.entryForId(UUID.fromString(token.associatedId.get))
        entriesForBulk(tokenValue, from, Some(limit.getOrElse(500)))
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
