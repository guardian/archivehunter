package controllers

import java.time.ZonedDateTime
import java.util.UUID

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{GraphDSL, RunnableGraph}
import akka.stream.{ActorMaterializer, ClosedShape, Materializer}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.google.inject.Injector
import com.gu.pandomainauth.action.UserRequest
import com.gu.scanamo.error.DynamoReadError
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, LightboxIndex, StorageClass, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import helpers.LightboxHelper.logger
import helpers.LightboxStreamComponents.{InitiateRestoreSink, LookupLightboxEntryFlow}
import helpers.{InjectableRefresher, LightboxHelper, SearchHitToArchiveEntryFlow}
import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses._
import io.circe.syntax._
import io.circe.generic.auto._
import models.{ServerTokenDAO, ServerTokenEntry, UserProfileDAO}
import play.api.libs.ws.WSClient
import play.mvc.Http.RequestHeader
import requests.SearchRequest
import services.GlacierRestoreActor
import services.GlacierRestoreActor.GRMsg

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class LightboxController @Inject() (override val config:Configuration,
                                    override val controllerComponents:ControllerComponents,
                                    override val wsClient:WSClient,
                                    override val refresher:InjectableRefresher,
                                    esClientMgr:ESClientManager,
                                    s3ClientMgr:S3ClientManager,
                                    @Named("glacierRestoreActor") glacierRestoreActor:ActorRef,
                                    lightboxBulkEntryDAO: LightboxBulkEntryDAO,
                                    serverTokenDAO: ServerTokenDAO,
                                    userProfileDAO: UserProfileDAO)
                                   (implicit val system:ActorSystem, injector:Injector, lightboxEntryDAO: LightboxEntryDAO)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe with ZonedDateTimeEncoder with RestoreStatusEncoder with AdminsOnly {
  private val logger=Logger(getClass)
  private implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val esClient = esClientMgr.getClient()
  private val s3Client = s3ClientMgr.getClient(awsProfile)
  private implicit val ec:ExecutionContext  = controllerComponents.executionContext
  private val indexName = config.get[String]("externalData.indexName")
  private implicit val mat:Materializer = ActorMaterializer.create(system)

  implicit val timeout:akka.util.Timeout = 30 seconds

  val tokenShortDuration = config.getOptional[Int]("serverToken.shortLivedDuration").getOrElse(10)  //default value is 2 hours

  def targetUserProfile[T](request:UserRequest[T], targetUser:String) = {
    val actualUserProfile = userProfileFromSession(request.session)
    if(targetUser=="my"){
      Future(actualUserProfile)
    } else {
      actualUserProfile match {
        case Some(Left(err))=>
          Future(Some(Left(err)))
        case Some(Right(someUserProfile))=>
          if(!someUserProfile.isAdmin){
            logger.error(s"Non-admin user is trying to access lightbox of $targetUser")
            Future(None)
          } else {
            userProfileDAO.userProfileForEmail(targetUser)
          }
        case None=>
          Future(None)
      }
    }
  }

  def removeFromLightbox(user:String, fileId:String) = APIAuthAction.async { request=>
    targetUserProfile(request, user).flatMap({
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
    })
  }

  def addFromSearch(user:String) = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SearchRequest].fold(
      err=> Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      searchReq=>
        targetUserProfile(request, user).flatMap({
          case None => Future(BadRequest(GenericErrorResponse("session_error", "no session present").asJson))
          case Some(Left(err)) =>
            logger.error(s"Session is corrupted: ${err.toString}")
            Future(InternalServerError(GenericErrorResponse("session_error", "session is corrupted, log out and log in again").asJson))
          case Some(Right(userProfile)) =>
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
                        LightboxHelper.addToBulkFromSearch(indexName,userProfile,request.user.avatarUrl,searchReq,savedEntry).flatMap(updatedBulkEntry=>{
                          lightboxBulkEntryDAO.put(updatedBulkEntry).map({
                            case None=>
                              Ok(ObjectCreatedResponse("ok","bulkLightboxEntry", updatedBulkEntry.id).asJson)
                            case Some(Right(oldValue))=>
                              Ok(ObjectCreatedResponse("ok","bulkLightboxEntry", updatedBulkEntry.id).asJson)
                            case Some(Left(err))=>
                              InternalServerError(GenericErrorResponse("db_error",err.toString).asJson)
                          })
                        }).recover({
                          case err:Throwable=>
                            logger.error("Could not save lightbox entry: ", err)
                            InternalServerError(GenericErrorResponse("error", err.toString).asJson)
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
        })
    )
  }

  def addToLightbox(user:String, fileId:String) = APIAuthAction.async { request=>
    targetUserProfile(request, user).flatMap({
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(userProfile)) =>
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
    })
  }

  def lightboxDetails(user:String) = APIAuthAction.async { request=>
    targetUserProfile(request, user).flatMap({
      case None => Future(BadRequest(GenericErrorResponse("session_error", "no session present").asJson))
      case Some(Left(err)) =>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error", "session is corrupted, log out and log in again").asJson))
      case Some(Right(userProfile)) =>
        lightboxEntryDAO.allForUser(userProfile.userEmail).map(results => {
          val errors = results.collect({ case Left(err) => err })
          if (errors.nonEmpty) {
            errors.foreach(err => logger.error(s"Could not retrieve lightbox details: ${err.toString}"))
            InternalServerError(ObjectListResponse("db_error", "error", errors.map(_.toString), errors.length).asJson)
          } else {
            //it's easier for the frontend to consume this if we convert to a mapping here
            val finalResult = results.collect({ case Right(entry) => entry }).map(entry => Tuple2(entry.fileId, entry)).toMap
            Ok(ObjectListResponse("ok", "lightboxEntry", finalResult, results.length).asJson)
          }
        })
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

  def checkRestoreStatus(user:String, fileId:String) = APIAuthAction.async { request=>
    implicit val timeout:akka.util.Timeout = 60 seconds

    targetUserProfile(request, user).flatMap({
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
    })
  }

  /**
    * returns bulk entries for the current user
    * @return
    */
  def myBulks(user:String) = APIAuthAction.async { request=>
    targetUserProfile(request, user).flatMap({
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(profile))=>
        lightboxBulkEntryDAO.entriesForUser(profile.userEmail).flatMap(results=>{
          val failures = results.collect({ case Left(err)=>err })
          if(failures.nonEmpty){
            Future(InternalServerError(GenericErrorResponse("error",failures.map(_.toString).mkString(",")).asJson))
          } else {
            LightboxHelper.getLooseCountForUser(indexName, request.user.email).map({
              case Left(err)=>
                logger.error(s"Could not look up count for loose lightbox items: $err")
                val successes = results.collect({ case Right(value)=>value }) ++ List(LightboxBulkEntry.forLoose(profile.userEmail, 0))
                Ok(ObjectListResponse("ok","lightboxBulk",successes,successes.length).asJson)
              case Right(count)=>
                val successes = results.collect({ case Right(value)=>value }) ++ List(LightboxBulkEntry.forLoose(profile.userEmail, count))
                Ok(ObjectListResponse("ok","lightboxBulk",successes,successes.length).asJson)
            })

          }
        })
    })
  }

  def deleteBulk(entryId:String) = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case None=>
        Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(profile))=>
        lightboxBulkEntryDAO.entryForId(UUID.fromString(entryId)).flatMap({
          case None=>
            Future(NotFound(GenericErrorResponse("not_found","No bulk with that ID is present").asJson))
          case Some(Right(entry))=>
            if(entry.userEmail==profile.userEmail || profile.isAdmin) {
              logger.info(s"Removing bulk entries for request $entry")
              LightboxHelper.removeBulkContents(indexName, profile, entry).flatMap(count=> {
                  logger.info(s"Deleting bulk request $entry")
                  lightboxBulkEntryDAO.delete(entryId).map(_ => {
                    Ok(GenericErrorResponse("ok", "item deleted").asJson)
                  })
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

  /**
    * check whether there is a bulk entry for the given collection and path, for the requesting user.
    * if nothing is found, a 200 response is still returned, but with a null in the entry field.
    * @return
    */
  def haveBulkEntryFor = APIAuthAction.async(circe.json(2048)) { request=>
    request.body.as[SearchRequest].fold(
      err=>Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      rq=>{
        if(rq.path.isDefined && rq.collection.isDefined) {
          val desc = s"${rq.collection.get}:${rq.path.get}"
          lightboxBulkEntryDAO.entryForDescAndUser(request.user.email,desc).map({
            case Left(err)=>InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
            case Right(Some(entry))=>Ok(ObjectGetResponse("ok","lightboxbulk",entry.id).asJson)
            case Right(None)=>Ok(ObjectGetResponseEmpty("notfound","lightboxbulk").asJson)
          })
        } else {
          Future(BadRequest(GenericErrorResponse("bad_request","You must set path and collection").asJson))
        }
      }
    )
  }

  private def makeDownloadToken(entryId:String, userEmail:String) = {
    val token = ServerTokenEntry.create(associatedId = Some(entryId),duration=tokenShortDuration, forUser = Some(userEmail))
    serverTokenDAO.put(token).map({
      case None =>
        Ok(ObjectCreatedResponse("ok", "link", s"archivehunter:bulkdownload:${token.value}").asJson)
      case Some(Right(_)) =>
        Ok(ObjectCreatedResponse("ok", "link", s"archivehunter:bulkdownload:${token.value}").asJson)
      case Some(Left(err)) =>
        logger.error(s"Could not save token to database: $err")
        InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
    })
  }

  def bulkDownloadInApp(entryId:String) = APIAuthAction.async { request=>
    implicit val lightboxEntryDAOImpl = lightboxEntryDAO
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(profile))=>
        if(entryId=="loose"){
          makeDownloadToken(entryId, request.user.email)
        } else {
          lightboxBulkEntryDAO.entryForId(UUID.fromString(entryId)).flatMap({
            case None =>
              Future(NotFound(GenericErrorResponse("not_found", "No bulk with that ID is present").asJson))
            case Some(Right(_)) =>
              //create a token that is valid for 10 seconds
              makeDownloadToken(entryId, request.user.email)
            case Some(Left(err)) =>
              logger.error(s"Could not look up bulk entry in dynamo: ${err.toString}")
              Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
          })
        }
    }
  }

  def redoRestore(user:String, fileId:String) = APIAuthAction.async { request=>
    targetUserProfile(request, user).flatMap({
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(profile))=>
        Future.sequence(Seq(
          lightboxEntryDAO.get(profile.userEmail, fileId),
          indexer.getById(fileId))).flatMap(results=>{
          val archiveEntry = results(1).asInstanceOf[ArchiveEntry]
          val lbEntryResponse = results.head.asInstanceOf[Option[Either[DynamoReadError, LightboxEntry]]]

          lbEntryResponse match {
            case Some(Right(lbEntry)) =>
              if (profile.perRestoreQuota.isDefined && (archiveEntry.size/1048576L) < profile.perRestoreQuota.get)
                (glacierRestoreActor ? GlacierRestoreActor.InitiateRestore(archiveEntry, lbEntry, None)).mapTo[GRMsg].map({
                  case GlacierRestoreActor.RestoreSuccess =>
                    Ok(GenericErrorResponse("ok", "restore initiaited").asJson)
                  case GlacierRestoreActor.RestoreFailure(err) =>
                    logger.error(s"Could not redo restore for $archiveEntry", err)
                    InternalServerError(GenericErrorResponse("error", err.toString).asJson)
                })
              else {
                profile.perRestoreQuota match {
                  case Some(userQuota)=>logger.warn(s"Can't restore $fileId: user's quota of $userQuota Mb is less than file size of ${archiveEntry.size/1048576L}Mb")
                  case None=>logger.warn(s"Can't restore $fileId: user has no quota")
                }
                Future(Forbidden(GenericErrorResponse("quota_exceeded", "This restore would exceed your quota").asJson))
              }
            case Some(Left(error)) =>
              Future(InternalServerError(GenericErrorResponse("db_error", error.toString).asJson))
            case None =>
              Future(InternalServerError(GenericErrorResponse("integrity_error", s"No lightbox entry available for file $fileId").asJson))
          }
        })
    })
  }

  //this is temporary and will be replaced in the update that brings in the new approval workflow
  def redoBulk(user:String, bulkId:String) = APIAuthAction.async { request =>

    adminsOnlyAsync(request, allowHmac = true) {
      logger.info("in redoBulk")
      targetUserProfile(request, user).flatMap({
        case None => Future(BadRequest(GenericErrorResponse("session_error", "no session present").asJson))
        case Some(Left(err)) =>
          logger.error(s"Session is corrupted: ${err.toString}")
          Future(InternalServerError(GenericErrorResponse("session_error", "session is corrupted, log out and log in again").asJson))
        case Some(Right(profile)) =>
          logger.info(s"running with user profile $profile")
          val sink = injector.getInstance(classOf[InitiateRestoreSink])

          val graph = RunnableGraph.fromGraph(GraphDSL.create(sink) { implicit builder =>
            resultSink =>
              import akka.stream.scaladsl.GraphDSL.Implicits._

              //the LightboxHelper methods call here also handle the special case of "loose" items
              val source = LightboxHelper.getElasticSource(LightboxHelper.lightboxSearch(indexName, Some(bulkId), profile.userEmail))

              val actualSource = builder.add(source)
              val converter = builder.add(new SearchHitToArchiveEntryFlow)
              val lbLookup = builder.add(new LookupLightboxEntryFlow(profile.userEmail))
              actualSource ~> converter ~> lbLookup ~> resultSink
              ClosedShape
          })

          val streamResult = graph.run()
          streamResult.map(processedCount => {
            logger.info(s"bulk redo completed, processsed $processedCount items")
            Ok(CountResponse("ok", "triggered re-restore of items", processedCount).asJson)
          })
      })
    }
  }
}
