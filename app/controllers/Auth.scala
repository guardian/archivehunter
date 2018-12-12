package controllers

import play.api.mvc._

import scala.concurrent.Future
import play.api.{Configuration, Logger}
import akka.actor.ActorSystem
import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import com.gu.pandomainauth.model.AuthenticatedUser
import com.gu.pandomainauth.service.{CookieUtils, GoogleAuthException}
import helpers.InjectableRefresher
import javax.inject.{Inject, Singleton}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import responses.GenericErrorResponse
import io.circe.generic.auto._
import io.circe.syntax._
import models.{UserProfile, UserProfileDAO}
import play.api.mvc.Results.Redirect

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class Auth @Inject() (
             override val controllerComponents: ControllerComponents,
             override val config: Configuration,
             override val wsClient: WSClient,
             override val refresher:InjectableRefresher,
             userProfileDAO:UserProfileDAO,
           ) extends AbstractController(controllerComponents) with PanDomainAuthActions with Circe {
  private val logger=Logger(getClass)

  /**
    * create a new userProfile object from the provided authentication data
    * @param u AuthenticatedUser instance to create the profile from
    * @return
    */
  def registerUser(u:AuthenticatedUser):Future[Either[String,UserProfile]] = {
    val newRecord = UserProfile(u.user.email,isAdmin = false, visibleCollections = Seq(), allCollectionsVisible = true)
    userProfileDAO.put(newRecord).map({
      case None=>
        Right(newRecord)
      case Some(Right(savedRecord))=>
        Right(savedRecord)
      case Some(Left(err))=>
        Left(err.toString)
    })
  }

  /**
    * handle the full oauthCallback here rather than delegating to PanDomainAuthActions.
    * This is to make it simpler to update the session with the user profile.
    * @return
    */
  def oauthCallback = Action.async { implicit request =>
    Logger.debug("processGoogleCallback")
    Logger.debug(request.session.toString)
    implicit val myExecContext = controllerComponents.executionContext
    val token =
      request.session.get(ANTI_FORGERY_KEY).getOrElse(throw new GoogleAuthException("missing anti forgery token"))
    val originalUrl =
      request.session.get(LOGIN_ORIGIN_KEY).getOrElse(throw new GoogleAuthException("missing original url"))

    val existingCookie = readCookie(request) // will be populated if this was a re-auth for expired login

    GoogleAuth.validatedUserIdentity(token)(request, controllerComponents.executionContext, wsClient).flatMap { claimedAuth =>
      Logger.debug(claimedAuth.toString)
      val authedUserData = existingCookie match {
        case Some(c) =>
          val existingAuth = CookieUtils.parseCookieData(c.value, panDomainSettings.settings.publicKey)
          Logger.debug("user re-authed, merging auth data")

          claimedAuth.copy(
            authenticatingSystem = panDomainSettings.system,
            authenticatedIn = existingAuth.authenticatedIn ++ Set(panDomainSettings.system),
            multiFactor = checkMultifactor(claimedAuth)
          )
        case None =>
          Logger.debug("fresh user login")
          claimedAuth.copy(multiFactor = checkMultifactor(claimedAuth))
      }

      if (validateUser(authedUserData)) {
        Logger.debug("User validated, adding cookies")
        val updatedCookies = generateCookies(authedUserData)
        Logger.debug(updatedCookies.toString())
        Logger.debug("Adding user profile to session")
        userProfileDAO.userProfileForEmail(authedUserData.user.email).flatMap({
          case None=> //user has not been registeted yet
            registerUser(authedUserData)
          case Some(Right(userProfile))=>
            Future(Right(userProfile))
          case Some(Left(err))=>
            Future(Left(err.toString))
        }).map({
          case Right(userProfile)=>
            Logger.debug(s"Redirecting back to $originalUrl")
            Redirect(originalUrl)
              .withCookies(updatedCookies: _*)
              .withSession(session = request.session - ANTI_FORGERY_KEY - LOGIN_ORIGIN_KEY + ("userProfile"->userProfile.asJson.toString))
          case Left(err)=>
            InternalServerError(GenericErrorResponse("error",err).asJson)
        })
      } else {
        Logger.error(s"User did not auth")
        Future(showUnauthedMessage(invalidUserMessage(claimedAuth)))
      }
    }
  }

  def logout = Action.async { implicit request =>
    Future (flushCookie(Ok(GenericErrorResponse("ok","logged out").asJson)))
  }
}