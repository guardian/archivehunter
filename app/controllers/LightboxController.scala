package controllers

import java.time.ZonedDateTime
import java.util.UUID
import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph}
import akka.stream.{ActorMaterializer, ClosedShape, Materializer}
import auth.{BearerTokenAuth, Security, UserRequest}
import com.google.inject.Injector
import org.scanamo.DynamoReadError
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, StorageClass, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import helpers.LightboxStreamComponents.{BulkRestoreStatsSink, ExtractArchiveEntry, InitiateRestoreSink, LightboxDynamoSource, LookupArchiveEntryFromLBEntryFlow, LookupLightboxEntryFlow, UpdateLightboxIndexInfoSink}
import helpers.{LightboxHelper, UserAvatarHelper}

import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents, Request, Result}
import responses._
import io.circe.syntax._
import io.circe.generic.auto._
import models.{ServerTokenDAO, ServerTokenEntry, UserProfile, UserProfileDAO}
import requests.SearchRequest
import services.GlacierRestoreActor
import services.GlacierRestoreActor.GRMsg

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import helpers.S3Helper.getPresignedURL
import play.api.cache.SyncCacheApi
import auth.ClaimsSetExtensions._
import org.slf4j.LoggerFactory
import software.amazon.awssdk.regions.Region

@Singleton
class LightboxController @Inject() (override val config:Configuration,
                                    override val controllerComponents:ControllerComponents,
                                    override val bearerTokenAuth: BearerTokenAuth,
                                    override val cache:SyncCacheApi,
                                    esClientMgr:ESClientManager,
                                    @Named("glacierRestoreActor") glacierRestoreActor:ActorRef,
                                    lightboxBulkEntryDAO: LightboxBulkEntryDAO,
                                    serverTokenDAO: ServerTokenDAO,
                                    dynamoClientManager:DynamoClientManager,
                                    userAvatarHelper:UserAvatarHelper)
                                   (implicit val system:ActorSystem,
                                    mat:Materializer,
                                    injector:Injector,
                                    s3ClientMgr:S3ClientManager,
                                    lightboxEntryDAO: LightboxEntryDAO,
                                    userProfileDAO: UserProfileDAO)
  extends AbstractController(controllerComponents) with Security with Circe with ZonedDateTimeEncoder with RestoreStatusEncoder {
  override protected val logger=LoggerFactory.getLogger(getClass)
  private implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private implicit val esClient = esClientMgr.getClient()
  private implicit val ec:ExecutionContext  = controllerComponents.executionContext
  private val indexName = config.get[String]("externalData.indexName")

  implicit val timeout:akka.util.Timeout = 30 seconds

  val tokenShortDuration = config.getOptional[Int]("serverToken.shortLivedDuration").getOrElse(10)  //default value is 2 hours
  val defaultLinkExpiry = 1800 //links expire after 30 minutes
  val defaultRegion = Region.of(config.get[String]("externalData.awsRegion"))

  def withTargetUserProfile[T](request:Request[T], user:String)(block: (UserProfile=>Future[Result])) =
    targetUserProfile(request, user).flatMap({
      case None => Future(BadRequest(GenericErrorResponse("session_error", "no session present").asJson))
      case Some(Left(err)) =>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error", "session is corrupted, log out and log in again").asJson))
      case Some(Right(profile)) =>
        block(profile)
    })

  def removeFromLightbox(user:String, fileId:String) = IsAuthenticatedAsync { claims=> request=>
    withTargetUserProfile(request, user) { profile=>
        val indexUpdateFuture = indexer.getById(fileId).flatMap(indexEntry => {
          val updatedEntry = indexEntry.copy(lightboxEntries = indexEntry.lightboxEntries.filter(_.owner!=profile.userEmail))
          indexer.indexSingleItem(updatedEntry, Some(updatedEntry.id))
        })

        val lbUpdateFuture = lightboxEntryDAO.delete(profile.userEmail, fileId)
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

  private def saveAndStartRestore(maybeBulkEntry:Either[DynamoReadError, LightboxBulkEntry],
                                  searchReq:SearchRequest,
                                  userProfile:UserProfile,
                                  user:String) = maybeBulkEntry match {
    case Left(err)=>
      logger.error(s"Could not get bulk restore entries: ${err.toString}")
      Future(InternalServerError(GenericErrorResponse("error", err.toString).asJson))
    case Right(entry)=>
      logger.info(s"Got bulk restore entry: $entry")

      lightboxBulkEntryDAO.put(entry)
        .flatMap(savedEntry=>{
          LightboxHelper
            .addToBulkFromSearch(indexName, userProfile, userAvatarHelper.getAvatarLocationString(userProfile.userEmail), searchReq, savedEntry)
            .flatMap(updatedBulkEntry => {
              lightboxBulkEntryDAO.put(updatedBulkEntry).map(_=>{
                  Ok(ObjectCreatedResponse("ok", "bulkLightboxEntry", updatedBulkEntry.id).asJson)
              })
            }).recover({
              case err: Throwable =>
                logger.error("Could not save lightbox entry: ", err)
                InternalServerError(GenericErrorResponse("error", err.toString).asJson)
            })
        }).recover({
          case err:Throwable=>
            logger.error(s"Could not save bulk restore entry: $err")
            InternalServerError(GenericErrorResponse("db_error",err.toString).asJson)
        })
  }

  private def getOrCreateBulkEntry(searchReq:SearchRequest, userProfile:UserProfile, user:String) = {
    //either pick up an existing bulk entry or create a new one
    val bulkDesc = s"${searchReq.collection.get}:${searchReq.path.getOrElse("none")}"
    lightboxBulkEntryDAO.entryForDescAndUser(userProfile.userEmail, bulkDesc)
      .map(_.map({
        case Some(entry)=>entry
        case None=>LightboxBulkEntry.create(userProfile.userEmail, bulkDesc)
      }))
  }

  def addFromSearch(user:String) = IsAuthenticatedAsync(circe.json(2048)) { claims=> request=>
    request.body.as[SearchRequest].fold(
      err=> Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      searchReq=>
        withTargetUserProfile(request, user) { userProfile=>
          LightboxHelper.testBulkAddSize(indexName ,userProfile, searchReq).flatMap({
            case Left(resp:QuotaExceededResponse)=>
              Future(new Status(413)(resp.asJson))
            case Right(restoreSize)=>
              logger.info(s"Proceeding with bulk restore of size $restoreSize")
              for {
                maybeLightboxBulkEntry <- getOrCreateBulkEntry(searchReq, userProfile, user)
                response <- saveAndStartRestore(maybeLightboxBulkEntry, searchReq, userProfile, user)
              } yield response
          }).recover({
            case err:Throwable=>
              logger.error("Could not test bulk add size: ", err)
              InternalServerError(GenericErrorResponse("error",err.toString).asJson)
          })
        }
    )
  }

  def addToLightbox(user:String, fileId:String) = IsAuthenticatedAsync { claims=> request=>
    withTargetUserProfile(request, user) { userProfile=>
        indexer.getById(fileId).flatMap(indexEntry =>
          Future.sequence(Seq(
            LightboxHelper.saveLightboxEntry(userProfile, indexEntry, None),
            LightboxHelper.updateIndexLightboxed(userProfile, userAvatarHelper.getAvatarLocationString(userProfile.userEmail), indexEntry, None)
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

  def lightboxDetails(user:String) = IsAuthenticatedAsync { claims=> request=>
    withTargetUserProfile(request, user) { userProfile=>
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
    }
  }

  /**
    * returns a presigned URL to download the requested media file. You must be logged in for this to work (obviously!)
    * @param fileId
    * @return
    */
  def getDownloadLink(fileId:String) = IsAuthenticatedAsync { claims=> request=>
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(userProfile))=>
        indexer.getById(fileId).map(archiveEntry=>{
          if(userProfile.allCollectionsVisible || userProfile.visibleCollections.contains(archiveEntry.bucket)){
            getPresignedURL(archiveEntry, defaultLinkExpiry, defaultRegion, awsProfile) match {
              case Success(url)=>
                Ok(ObjectGetResponse("ok","link",url.toString).asJson)
              case Failure(ex)=>
                logger.error("Could not generate presigned s3 url: ", ex)
                InternalServerError(GenericErrorResponse("error",ex.toString).asJson)
            }
          } else {
            Forbidden(GenericErrorResponse("forbidden", "You don't have access to the right catalogue to do this").asJson)
          }
        })
    }
  }

  def checkRestoreStatus(user:String, fileId:String) = IsAuthenticatedAsync { claims=> request=>
    implicit val timeout:akka.util.Timeout = 60 seconds

    withTargetUserProfile(request, user) { userProfile=>
      lightboxEntryDAO.get(userProfile.userEmail, fileId).flatMap({
        case None=>
          Future(NotFound(GenericErrorResponse("not_found","This item is not in your lightbox").asJson))
        case Some(Left(err))=>
          Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
        case Some(Right(lbEntry))=>
          val response = (glacierRestoreActor ? GlacierRestoreActor.CheckRestoreStatus(lbEntry)).mapTo[GlacierRestoreActor.GRMsg]
          response.map({
            case GlacierRestoreActor.ItemLost(_)=>
              NotFound(GenericErrorResponse("not_found","Item must have gone away").asJson)
            case GlacierRestoreActor.NotInArchive(entry)=>
              Ok(RestoreStatusResponse("ok",entry.id, RestoreStatus.RS_UNNEEDED, None, None).asJson)
            case GlacierRestoreActor.RestoreCompleted(entry, expiry)=>
              Ok(RestoreStatusResponse("ok", entry.id, RestoreStatus.RS_SUCCESS, Some(expiry), None).asJson)
            case GlacierRestoreActor.RestoreInProgress(entry)=>
              Ok(RestoreStatusResponse("ok", entry.id, RestoreStatus.RS_UNDERWAY, None, None).asJson)
            case GlacierRestoreActor.RestoreNotRequested(entry)=>
              val restoreStatus = if(entry.storageClass==StorageClass.GLACIER) {
                RestoreStatus.RS_EXPIRED
              } else {
                RestoreStatus.RS_UNNEEDED
              }
              Ok(RestoreStatusResponse("not_requested", entry.id, restoreStatus, None, None).asJson)
          })
      })
    }
  }

  /**
    * checks the restore status of everything in the given bulk and returns a set of stats
    * @param user user whose lightbox we are checking
    * @param bulkId bulk ID we are checking
    * @return
    */
  def bulkCheckRestoreStatus(user:String, bulkId:String) = IsAuthenticatedAsync { claims=> request=>
    withTargetUserProfile(request, user) { userProfile=>
      import akka.stream.scaladsl.GraphDSL.Implicits._
      val sinkFactory = injector.getInstance(classOf[BulkRestoreStatsSink])

      val graph = RunnableGraph.fromGraph(GraphDSL.create(sinkFactory) { implicit builder => sink=>
        val src = new LightboxDynamoSource(bulkId, config, dynamoClientManager)
        val actualSrc = builder.add(src)

        actualSrc ~> sink
        ClosedShape
      })

      graph.run().map(stats=>{
        Ok(ObjectGetResponse("ok","restore_stats", stats).asJson)
      }).recover({
        case ex:Throwable=>
          logger.error(s"Could not run stream to check bulk restore status: ", ex)
          InternalServerError(GenericErrorResponse("error", ex.toString).asJson)
      })

    }
  }

  /**
    * returns bulk entries for the current user
    * @return
    */
  def myBulks(user:String) = IsAuthenticatedAsync { username=> request=>
    import cats.implicits._
    withTargetUserProfile(request, user) { profile=>
        lightboxBulkEntryDAO.entriesForUser(profile.userEmail).flatMap(results=>{
          val failures = results.collect({ case Left(err)=>err })
          if(failures.nonEmpty){
            Future(InternalServerError(GenericErrorResponse("error",failures.map(_.toString).mkString(",")).asJson))
          } else {
            request.session.get("username")
              .map(userEmail=>LightboxHelper.getLooseCountForUser(indexName, userEmail))
              .sequence
              .map({
              case Some(Left(err))=>
                logger.error(s"Could not look up count for loose lightbox items: $err")
                val successes = results.collect({ case Right(value)=>value }) ++ List(LightboxBulkEntry.forLoose(profile.userEmail, 0))
                Ok(ObjectListResponse("ok","lightboxBulk",successes,successes.length).asJson)
              case Some(Right(count))=>
                val successes = results.collect({ case Right(value)=>value }) ++ List(LightboxBulkEntry.forLoose(profile.userEmail, count))
                Ok(ObjectListResponse("ok","lightboxBulk",successes,successes.length).asJson)
              case None=>
                BadRequest(GenericErrorResponse("auth_problem", "User information has no email address").asJson)
            })

          }
        })
    }
  }

  def deleteBulk(user:String, entryId:String) = IsAuthenticatedAsync { claims=> request=>
    withTargetUserProfile(request, user) { profile=>
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
  def haveBulkEntryFor(user:String) = IsAuthenticatedAsync(circe.json(2048)) { username=> request=>
    request.body.as[SearchRequest].fold(
      err=>Future(BadRequest(GenericErrorResponse("bad_request", err.toString).asJson)),
      rq=>{
        if(rq.path.isDefined && rq.collection.isDefined) {
          val desc = s"${rq.collection.get}:${rq.path.get}"
          lightboxBulkEntryDAO.entryForDescAndUser(username,desc).map({
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
    serverTokenDAO.put(token).map(_=> {
      Ok(ObjectCreatedResponse("ok", "link", s"archivehunter:bulkdownload:${token.value}").asJson)
    }).recover({
      case err:Throwable =>
        logger.error(s"Could not save token to database: $err")
        InternalServerError(GenericErrorResponse("db_error", "Could not save token, see logs").asJson)
    })
  }

  def bulkDownloadInApp(entryId:String) = IsAuthenticatedAsync { username=> request=>
    implicit val lightboxEntryDAOImpl = lightboxEntryDAO
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(profile))=>
        if(entryId=="loose"){
          makeDownloadToken(entryId, username)
        } else {
          lightboxBulkEntryDAO.entryForId(UUID.fromString(entryId)).flatMap({
            case None =>
              Future(NotFound(GenericErrorResponse("not_found", "No bulk with that ID is present").asJson))
            case Some(Right(_)) =>
              //create a token that is valid for 10 seconds
              makeDownloadToken(entryId, username)
            case Some(Left(err)) =>
              logger.error(s"Could not look up bulk entry in dynamo: ${err.toString}")
              Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
          })
        }
    }
  }

  def redoRestore(user:String, fileId:String) = IsAuthenticatedAsync { claims=> request=>
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

  def verifyBulkLightbox(user:String, bulkId:String) = IsAuthenticatedAsync { claims=> request=>
    withTargetUserProfile(request, user) { userProfile =>
      val sinkFactory = new UpdateLightboxIndexInfoSink(bulkId, userProfile, userAvatarHelper.getAvatarLocationString(user))

      val graph = RunnableGraph.fromGraph(GraphDSL.create(sinkFactory) { implicit builder=> sink=>
        import akka.stream.scaladsl.GraphDSL.Implicits._

        val src = new LightboxDynamoSource(bulkId, config, dynamoClientManager)
        val lbConverter = injector.getInstance(classOf[LookupArchiveEntryFromLBEntryFlow])

        val actualSource = builder.add(src)
        val actualConverter = builder.add(lbConverter)
        val actualExtractor = builder.add(new ExtractArchiveEntry)

        actualSource ~> actualConverter ~> actualExtractor ~> sink.in
        ClosedShape
      })

      val streamResult = graph.run()

      streamResult.map(foundItemCount=>{
        Ok(CountResponse("ok","updated items", foundItemCount).asJson)
      }).recover({
        case err:Throwable=>
          logger.error("Could not run stream to fix lightbox entries: ", err)
          InternalServerError(GenericErrorResponse("error",err.toString).asJson)
      })
    }
  }

  //this is temporary and will be replaced in the update that brings in the new approval workflow
  def redoBulk(user:String, bulkId:String) = IsAdminAsync { claims=> request =>
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

              val source = new LightboxDynamoSource(bulkId,config, dynamoClientManager)
              val lookup = injector.getInstance(classOf[LookupArchiveEntryFromLBEntryFlow])
              val actualSource = builder.add(source)
              val actualLookup = builder.add(lookup)

              actualSource ~> actualLookup ~> resultSink
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
