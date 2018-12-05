package controllers

import play.api.mvc._

import scala.concurrent.Future
import play.api.{Configuration, Logger}
import akka.actor.ActorSystem
import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import helpers.InjectableRefresher
import javax.inject.{Inject, Singleton}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import responses.GenericErrorResponse
import io.circe.generic.auto._
import io.circe.syntax._

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class Auth @Inject() (
             override val controllerComponents: ControllerComponents,
             override val config: Configuration,
             override val wsClient: WSClient,
             override val refresher:InjectableRefresher
           ) extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe {
  private val logger=Logger(getClass)

  def oauthCallback = Action.async { implicit request =>
    logger.debug("received oauthCallback")
    processGoogleCallback()
  }

  def logout = Action.async { implicit request =>
    Future (flushCookie(Ok(GenericErrorResponse("ok","logged out").asJson)))
  }
}