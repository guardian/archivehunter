package controllers

import java.time.ZonedDateTime

import com.theguardian.multimedia.archivehunter.common.{Indexer, LightboxIndex, StorageClass, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.InjectableRefresher
import javax.inject.{Inject, Singleton}
import models.{LightboxEntry, LightboxEntryDAO, RestoreStatus, RestoreStatusEncoder}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectListResponse}
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class LightboxController @Inject() (override val config:Configuration,
                                    lightboxEntryDAO: LightboxEntryDAO,
                                    override val controllerComponents:ControllerComponents,
                                    override val wsClient:WSClient,
                                    override val refresher:InjectableRefresher,
                                    esClientMgr:ESClientManager)
  extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe with ZonedDateTimeEncoder with RestoreStatusEncoder {
  private val logger=Logger(getClass)
  private val indexer = new Indexer(config.get[String]("externalData.indexName"))
  private implicit val esClient = esClientMgr.getClient()
  private implicit val ec:ExecutionContext  = controllerComponents.executionContext

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

  def addToLightbox(fileId:String) = APIAuthAction.async { request=>
    userProfileFromSession(request.session) match {
      case None=>Future(BadRequest(GenericErrorResponse("session_error","no session present").asJson))
      case Some(Left(err))=>
        logger.error(s"Session is corrupted: ${err.toString}")
        Future(InternalServerError(GenericErrorResponse("session_error","session is corrupted, log out and log in again").asJson))
      case Some(Right(userProfile)) =>
        indexer.getById(fileId).flatMap(indexEntry => {
          val expectedRestoreStatus = indexEntry.storageClass match {
            case StorageClass.GLACIER => RestoreStatus.RS_PENDING
            case _ => RestoreStatus.RS_UNNEEDED
          }

          val lbEntry = LightboxEntry(userProfile.userEmail, fileId, ZonedDateTime.now(), expectedRestoreStatus, None, None, None, None)
          val lbSaveFuture = lightboxEntryDAO.put(lbEntry).map({
            case None=>
              logger.debug(s"lightbox entry saved, no return")
              Success("saved")
            case Some(Right(value))=>
              logger.debug(s"lightbox entry saved, returned $value")
              Success("saved")
            case Some(Left(err))=>
              logger.error(s"Could not save lightbox entry: ${err.toString}")
              Failure(new RuntimeException(err.toString))
          })

          val lbIndex = LightboxIndex(request.user.email,request.user.avatarUrl, ZonedDateTime.now())
          logger.debug(s"lbIndex is $lbIndex")
          val updatedEntry = indexEntry.copy(lightboxEntries = indexEntry.lightboxEntries ++ Seq(lbIndex))
          logger.debug(s"updateEntry is $updatedEntry")
          val indexUpdateFuture = indexer.indexSingleItem(updatedEntry,Some(updatedEntry.id))

          Future.sequence(Seq(lbSaveFuture, indexUpdateFuture)).map(results=>{
            val errors = results.collect({case Failure(err)=>err})
            if(errors.nonEmpty){
              errors.foreach(err=>logger.error("Could not create lightbox entry", err))
              InternalServerError(ObjectListResponse("error","errors",errors.map(_.toString), errors.length).asJson)
            } else {
              Ok(GenericErrorResponse("ok","saved").asJson)
            }
          })
        })
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

}
