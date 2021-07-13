package controllers

import auth.BearerTokenAuth
import play.api.Configuration
import play.api.mvc.{AbstractController, ControllerComponents}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Auth @Inject() (config:Configuration, bearerTokenAuth: BearerTokenAuth, cc:ControllerComponents) extends AbstractController(cc) {
  private implicit val ec:ExecutionContext = cc.executionContext

  def oauthCallback() = Action.async {
    Future(BadRequest("not implemented yet"))
  }

  def logout() = Action {
    BadRequest("not implemented yet")
  }
}
