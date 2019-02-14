package controllers

import java.time.ZonedDateTime

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.{Indexer, LightboxIndex, StorageClass, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, LightboxEntryDAO, RestoreStatus, RestoreStatusEncoder}
import helpers.LightboxHelper.logger
import helpers.{InjectableRefresher, LightboxHelper}
import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses._
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.libs.ws.WSClient
import requests.SearchRequest
import services.GlacierRestoreActor

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class LightboxController @Inject() (override val config:Configuration,
                                    lightboxEntryDAO: LightboxEntryDAO,
                                    override val controllerComponents:ControllerComponents,
                                    override val wsClient:WSClient,
                                    override val refresher:InjectableRefresher,
                                    esClientMgr:ESClientManager,
                                    s3ClientMgr:S3ClientManager,
                                    @Named("glacierRestoreActor") glacierRestoreActor:ActorRef)
                                   (implicit val system:ActorSystem)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe with ZonedDateTimeEncoder with RestoreStatusEncoder {
  private val logger=Logger(getClass)
  private val indexer = new Indexer(config.get[String]("externalData.indexName"))
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val esClient = esClientMgr.getClient()
  private val s3Client = s3ClientMgr.getClient(awsProfile)
  private implicit val ec:ExecutionContext  = controllerComponents.executionContext

  private implicit val mat:Materializer = ActorMaterializer.create(system)

  def removeFromLightbox(fileId:String) = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case None => Future(BadRequest(GenericErrorResponse("session_error", "no session present").asJson))
      case Some(Left(err)) =>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error", "session is corrupted, log out and log in again").asJson))
      case Some(Right(profile)) =>
        val indexUpdateFuture = indexer.getById(fileId).flatMap(indexEntry => {
          val updatedEntry = indexEntry.copy(lightboxEntries = indexEntry.lightboxEntries.filter(_.owner!=request.user.email))
          indexer.indexSingleItem(updatedEntry, Some(updatedEntry.id))
        })

        val lbUpdateFuture = lightboxEntryDAO.delete(request.user.email, fileId)
          .map(result=>Success(result.toString))
          .recoverWith({
            case err:Throwable=>Future(Failure(err))
          })

        Future.sequence(Seq(indexUpdateFuture,lbUpdateFuture)).map(results=>{
          val errors = results.collect({case Failure(err)=>err})
          if(errors.nonEmpty){
            errors.foreach(err=>logger.error("Could not remove from lightbox", err))
            InternalServerError(ObjectListResponse("error","errors",errors.map(_.toString), errors.length).asJson)
          } else {
            Ok(GenericErrorResponse("ok","removed").asJson)
          }
        })
    }
  }

  def addFromSearch = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SearchRequest].fold(
      err=> Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      searchReq=>
        userProfileFromSession(request.session) match {
          case None => Future(BadRequest(GenericErrorResponse("session_error", "no session present").asJson))
          case Some(Left(err)) =>
            logger.error(s"Session is corrupted: ${err.toString}")
            Future(InternalServerError(GenericErrorResponse("session_error", "session is corrupted, log out and log in again").asJson))
          case Some(Right(userProfile)) =>
            logger.info(s"Checking size of $searchReq")
            LightboxHelper.getTotalSizeOfSearch(config.get[String]("externalData.indexName"),searchReq)
              .map(totalSize=>{
                val totalSizeMb = totalSize/1048576L
                logger.info(s"Total size is $totalSizeMb Mb, userQuota is ${userProfile.perRestoreQuota.getOrElse(0L)}Mb")
                if(totalSizeMb > userProfile.perRestoreQuota.getOrElse(0L)) {
                  new Status(413)(QuotaExceededResponse("quota_exceeded","Your per-request quota has been exceeded",totalSizeMb, userProfile.perRestoreQuota.getOrElse(0)).asJson)
                } else {
                  Ok(GenericErrorResponse("ok","restore would proceed").asJson)
                }
              })
        }
    )
  }

  def addToLightbox(fileId:String) = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(userProfile)) =>
        implicit val lbEntryDAOImplicit = lightboxEntryDAO
        implicit val indexerImplicit = indexer
        indexer.getById(fileId).flatMap(indexEntry =>
          Future.sequence(Seq(
            LightboxHelper.saveLightboxEntry(userProfile, indexEntry),
            LightboxHelper.updateIndexLightboxed(userProfile, request.user.avatarUrl, indexEntry)
          )).map(results=>{
            val errors = results.collect({case Failure(err)=>err})
            if(errors.nonEmpty){
              errors.foreach(err=>logger.error("Could not create lightbox entry", err))
              InternalServerError(ObjectListResponse("error","errors",errors.map(_.toString), errors.length).asJson)
            } else {
              val lbEntry = results.head.asInstanceOf[Try[LightboxEntry]].get
              if(indexEntry.storageClass==StorageClass.GLACIER){
                glacierRestoreActor ! GlacierRestoreActor.InitiateRestore(indexEntry, lbEntry, None)  //use default expiration
              }
              Ok(GenericErrorResponse("ok","saved").asJson)
            }
          })
        )
    }
  }

  def lightboxDetails = APIAuthAction.async { request=>
    lightboxEntryDAO.allForUser(request.user.email).map(results=>{
      val errors = results.collect({case Left(err)=>err})
      if(errors.nonEmpty){
        errors.foreach(err=>logger.error(s"Could not retrieve lightbox details: ${err.toString}"))
        InternalServerError(ObjectListResponse("db_error","error",errors.map(_.toString), errors.length).asJson)
      } else {
        //it's easier for the frontend to consume this if we convert to a mapping here
        val finalResult = results.collect({case Right(entry)=>entry}).map(entry=>Tuple2(entry.fileId,entry)).toMap
        Ok(ObjectListResponse("ok","lightboxEntry", finalResult, results.length).asJson)
      }
    })
  }

  def getDownloadLink(fileId:String) = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(userProfile))=>
        indexer.getById(fileId).map(archiveEntry=>{
          if(userProfile.allCollectionsVisible || userProfile.visibleCollections.contains(archiveEntry.bucket)){
            try {
              val rq = new GeneratePresignedUrlRequest(archiveEntry.bucket, archiveEntry.path, HttpMethod.GET)
                .withResponseHeaders(new ResponseHeaderOverrides().withContentDisposition("attachment"))
              val response = s3Client.generatePresignedUrl(rq)
              Ok(ObjectGetResponse("ok","link",response.toString).asJson)
            } catch {
              case ex:Throwable=>
                logger.error("Could not generate presigned s3 url: ", ex)
                InternalServerError(GenericErrorResponse("error",ex.toString).asJson)
            }
          } else {
            Forbidden(GenericErrorResponse("forbidden", "You don't have access to the right catalogue to do this").asJson)
          }
        })
    }
  }

  def checkRestoreStatus(fileId:String) = APIAuthAction.async { request=>
    implicit val timeout:akka.util.Timeout = 60 seconds

    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(userProfile))=>
        lightboxEntryDAO.get(userProfile.userEmail, fileId).flatMap({
          case None=>
            Future(NotFound(GenericErrorResponse("not_found","This item is not in your lightbox").asJson))
          case Some(Left(err))=>
            Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
          case Some(Right(lbEntry))=>
            val response = (glacierRestoreActor ? GlacierRestoreActor.CheckRestoreStatus(lbEntry)).mapTo[GlacierRestoreActor.GRMsg]
            response.map({
              case GlacierRestoreActor.NotInArchive(entry)=>
                Ok(RestoreStatusResponse("ok",entry.id, RestoreStatus.RS_UNNEEDED, None).asJson)
              case GlacierRestoreActor.RestoreCompleted(entry, expiry)=>
                Ok(RestoreStatusResponse("ok", entry.id, RestoreStatus.RS_SUCCESS, Some(expiry)).asJson)
              case GlacierRestoreActor.RestoreInProgress(entry)=>
                Ok(RestoreStatusResponse("ok", entry.id, RestoreStatus.RS_UNDERWAY, None).asJson)
              case GlacierRestoreActor.RestoreNotRequested(entry)=>
                Ok(RestoreStatusResponse("not_requested", entry.id, RestoreStatus.RS_ERROR, None).asJson)
            })

        })
    }
  }
}
