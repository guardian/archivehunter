package controllers

import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import com.sun.org.apache.xerces.internal.xs.datatypes.ObjectList
import helpers.InjectableRefresher
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectListResponse}
import io.circe.syntax._
import io.circe.generic.auto._
import models.UserProfileDAO

import scala.concurrent.Future

@Singleton
class UserController @Inject()(override val controllerComponents:ControllerComponents,
                               override val wsClient: WSClient,
                               override val config: Configuration,
                               override val refresher:InjectableRefresher,
                               userProfileDAO:UserProfileDAO)
  extends AbstractController(controllerComponents) with PanDomainAuthActions  with Circe {
  implicit val ec = controllerComponents.executionContext

  def loginStatus = APIAuthAction { request =>
    val user = request.user
    Ok(user.toJson)
  }

  def allUsers = APIAuthAction.async {request=>
    userProfileFromSession(request.session).flatMap({
      case None=>
        Future(Forbidden(GenericErrorResponse("error","Not logged in").asJson))
      case Some(Left(err))=>
        Future(InternalServerError(GenericErrorResponse("error","Session is corrupted. Please log out and log in again").asJson))
      case Some(Right(profile))=>
        if(profile.isAdmin){
          userProfileDAO.allUsers().map(resultList=>{
            val errors = resultList.collect({case Left(err)=>err})
            if(errors.nonEmpty){
              InternalServerError(GenericErrorResponse("db_error", errors.map(_.toString).mkString(",")).asJson)
            } else {
              Ok(ObjectListResponse("ok","user",resultList.collect({case Right(up)=>up}), resultList.length).asJson)
            }
          })
        } else {
          Future(Forbidden(GenericErrorResponse("not_allowed","Only admins are allowed to perform this action").asJson))
        }
    })
  }
}
