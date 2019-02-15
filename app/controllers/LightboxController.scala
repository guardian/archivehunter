package controllers

import java.time.ZonedDateTime
import java.util.UUID

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.{Indexer, LightboxIndex, StorageClass, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
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
                                    @Named("glacierRestoreActor") glacierRestoreActor:ActorRef,
                                    lightboxBulkEntryDAO: LightboxBulkEntryDAO)
                                   (implicit val system:ActorSystem)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe with ZonedDateTimeEncoder with RestoreStatusEncoder {
  private val logger=Logger(getClass)
  private implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val esClient = esClientMgr.getClient()
  private val s3Client = s3ClientMgr.getClient(awsProfile)
  private implicit val ec:ExecutionContext  = controllerComponents.executionContext
  private val indexName = config.get[String]("externalData.indexName")
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
            implicit val implLBEntryDAO = lightboxEntryDAO
            LightboxHelper.testBulkAddSize(indexName ,userProfile, searchReq).flatMap({
              case Left(resp:QuotaExceededResponse)=>
                Future(new Status(413)(resp.asJson))
              case Right(restoreSize)=>
                logger.info("Proceeding with bulk restore")

                //either pick up an existing bulk entry or create a new one
                val bulkDesc = s"${searchReq.collection.get}:${searchReq.path.getOrElse("none")}"
                val maybeBulkEntryFuture = lightboxBulkEntryDAO.entryForDescAndUser(userProfile.userEmail, bulkDesc)
                    .map(_.map({
                      case Some(entry)=>entry
                      case None=>LightboxBulkEntry.create(userProfile.userEmail, bulkDesc)
                    }))

                maybeBulkEntryFuture.flatMap({
                  case Left(err)=>
                    logger.error(s"Could not get bulk restore entires: ${err.toString}")
                    Future(InternalServerError(GenericErrorResponse("error", err.toString).asJson))
                  case Right(entry)=>
                    logger.info(s"Got bulk restore entry: $entry")
                    val saveFuture = lightboxBulkEntryDAO.put(entry).map({
                      case None=>Right(entry)
                      case Some(Right(x))=>Right(x)
                      case Some(Left(err))=>Left(err)
                    })

                    saveFuture.flatMap({
                      case Right(savedEntry)=>
                        LightboxHelper.addToBulkFromSearch(indexName,userProfile,searchReq,savedEntry).flatMap(updatedBulkEntry=>{
                          lightboxBulkEntryDAO.put(updatedBulkEntry).map({
                            case None=>
                              Ok(ObjectCreatedResponse("ok","bulkLightboxEntry", updatedBulkEntry.id).asJson)
                            case Some(Right(oldValue))=>
                              Ok(ObjectCreatedResponse("ok","bulkLightboxEntry", updatedBulkEntry.id).asJson)
                            case Some(Left(err))=>
                              InternalServerError(GenericErrorResponse("db_error",err.toString).asJson)
                          })

                        })

                      case Left(err)=>
                        logger.error(s"Could not save bulk restore entry: $err")
                        Future(InternalServerError(GenericErrorResponse("db_error",err.toString).asJson))
                    })

                })

            }).recover({
              case err:Throwable=>
                logger.error("Could not test bulk add size: ", err)
                InternalServerError(GenericErrorResponse("error",err.toString).asJson)
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
        indexer.getById(fileId).flatMap(indexEntry =>
          Future.sequence(Seq(
            LightboxHelper.saveLightboxEntry(userProfile, indexEntry, None),
            LightboxHelper.updateIndexLightboxed(userProfile, request.user.avatarUrl, indexEntry, None)
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

  /**
    * returns bulk entries for the current user
    * @return
    */
  def myBulks = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(profile))=>
        lightboxBulkEntryDAO.entriesForUser(profile.userEmail).map(results=>{
          val failures = results.collect({ case Left(err)=>err })
          if(failures.nonEmpty){
            InternalServerError(GenericErrorResponse("error",failures.map(_.toString).mkString(",")).asJson)
          } else {
            val successes = results.collect({ case Right(value)=>value })
            Ok(ObjectListResponse("ok","lightboxBulk",successes,successes.length).asJson)
          }
        })
    }
  }

  def deleteBulk(entryId:String) = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(profile))=>
        lightboxBulkEntryDAO.entryForId(UUID.fromString(entryId)).flatMap({
          case None=>
            Future(NotFound(GenericErrorResponse("not_found","No bulk with that ID is present").asJson))
          case Some(Right(entry))=>
            if(entry.userEmail==profile.userEmail || profile.isAdmin) {
              logger.info(s"Deleting bulk request $entry")
              lightboxBulkEntryDAO.delete(entryId).map(deleteResult => {
                Ok(GenericErrorResponse("ok", "item deleted").asJson)
              }).recover({
                case err: Throwable =>
                  logger.error("Could not delete record from dynamo: ", err)
                  InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
              })
            } else {
              Future(Forbidden(GenericErrorResponse("forbidden", "You don't have permission to do this, please contact your administrator").asJson))
            }
          case Some(Left(err))=>
            logger.error(s"Could not look up bulk entry in dynamo: ${err.toString}")
            Future(InternalServerError(GenericErrorResponse("db_error",err.toString).asJson))
          })
    }
  }
}
