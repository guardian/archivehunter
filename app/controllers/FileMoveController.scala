
package controllers

import akka.actor.{ActorRef, ActorSystem}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModelDAO, ScanTargetDAO}

import javax.inject.{Inject, Named}
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectGetResponse}
import akka.pattern.ask
import auth.{BearerTokenAuth, Security}
import services.FileMove.GenericMoveActor.MoveActorMessage
import services.{FileMoveActor, FileMoveQueue}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._
import play.api.cache.SyncCacheApi

class FileMoveController @Inject()(override val config:Configuration,
                                   override val controllerComponents:ControllerComponents,
                                   override val bearerTokenAuth: BearerTokenAuth,
                                   override val cache:SyncCacheApi,
                                   scanTargetDAO:ScanTargetDAO,
                                   @Named("fileMoveQueue") fileMoveQueue:ActorRef)
                                  (implicit actorSystem:ActorSystem)
  extends AbstractController(controllerComponents) with Security with Circe {

  import FileMoveQueue._

  override protected val logger=LoggerFactory.getLogger(getClass)
  private implicit val akkaTimeout:akka.util.Timeout = 60 seconds

  /**
    * initiate a file move operation by calling out to the Actor
    * @param fileId file ID to move
    * @param to name of the Scan Target to move it to
    * @return 404 if the scan target does not exist, 500 if the move failed or 200 if it succeeded
    */
  def moveFile(fileId:String, to:String) = IsAuthenticatedAsync { uid=> request=>
    scanTargetDAO.targetForBucket(to).flatMap({
      case None=>
        Future(NotFound(GenericErrorResponse("not_found","No scan target with that name").asJson))
      case Some(Left(dbError))=>
        logger.error(s"Could not look up scan target in dynamo: ${dbError.toString}")
        Future(InternalServerError(GenericErrorResponse("db_error",dbError.toString).asJson))
      case Some(Right(scanTarget))=>
        if(scanTarget.enabled) {
          (fileMoveQueue ? EnqueueMove(fileId, scanTarget.bucketName, uid)).mapTo[FileMoveResponse]
            .map({
              case EnqueuedOk(_) => Ok(GenericErrorResponse("ok", "move is enqueued").asJson)
              case EnqueuedProblem(_, problem) => InternalServerError(GenericErrorResponse("error", problem).asJson)
            })
            .recover({
              case err: Throwable =>
                logger.error(s"Could not enqueue move action: ${err.getMessage}", err)
                InternalServerError(GenericErrorResponse("error", err.getMessage).asJson)
            })
        } else {
          Future(Conflict(GenericErrorResponse("disabled","this scan target is disabled").asJson))
        }
    })
  }
}
