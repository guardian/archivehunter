package controllers

import com.gu.pandomainauth.action.UserRequest
import models.UserProfile
import play.api.mvc.{Request, Result, Session}
import responses.GenericErrorResponse
import io.circe.syntax._
import io.circe.generic.auto._
import play.api.libs.circe.Circe

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.Results._

trait AdminsOnly extends Circe {
  def userProfileFromSession(session: Session):Option[Either[io.circe.Error, UserProfile]]

  /**
    * filter your response through this to only be called if the logged in user is an admin.
    * example:
    * def myAction = APIAuthAction.async { request=>
    *   adminsOnlyAsync(request) {
    *      {admins-only-code-in-here}
    *   }
    * }
    * the code block will only be called if the session is valid and the user is an admin.  Otherwise, a Forbidden error will be returned.
    * @param request Play request parameter
    * @param block code block to be called if the user is an admin. This should return a Future[Result].
    * @tparam A type of request body, implicit
    * @return a Play Response object.
    */
  def adminsOnlyAsync[A](request: Request[A])(block: => Future[Result])(implicit ec:ExecutionContext) = {
    userProfileFromSession(request.session) match {
      case None=>
        Future(Forbidden(GenericErrorResponse("error","Not logged in").asJson))
      case Some(Left(err))=>
        Future(InternalServerError(GenericErrorResponse("error","Session is corrupted. Please log out and log in again").asJson))
      case Some(Right(profile))=>
        if(profile.isAdmin){
          block
        } else {
          Future(Forbidden(GenericErrorResponse("not_allowed","Only admins are allowed to perform this action").asJson))
        }
    }
  }

  def adminsOnlySync[A](request: Request[A])(block: => Result)(implicit ec:ExecutionContext) = {
    userProfileFromSession(request.session) match {
      case None=>
        Forbidden(GenericErrorResponse("error","Not logged in").asJson)
      case Some(Left(err))=>
        InternalServerError(GenericErrorResponse("error","Session is corrupted. Please log out and log in again").asJson)
      case Some(Right(profile))=>
        if(profile.isAdmin){
          block
        } else {
          Forbidden(GenericErrorResponse("not_allowed","Only admins are allowed to perform this action").asJson)
        }
    }
  }

}
