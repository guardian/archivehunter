package controllers

import com.gu.pandomainauth.{PanDomain, PanDomainAuthSettingsRefresher, PublicSettings}
import com.gu.pandomainauth.action.{AuthActions, UserRequest}
import com.gu.pandomainauth.model._
import helpers.InjectableRefresher
import io.circe.ParsingFailure
import models.{UserProfile, UserProfileDAO}
import play.api.mvc._
import play.api.Configuration
import play.api.Logger
import io.circe.generic.auto._


trait PanDomainAuthActions extends AuthActions {
  val refresher:InjectableRefresher
  def config: Configuration
  def controllerComponents: ControllerComponents

  override def panDomainSettings: PanDomainAuthSettingsRefresher = refresher.panDomainSettings

  override def validateUser(authedUser: AuthenticatedUser): Boolean =
    authedUser.user.email endsWith "@guardian.co.uk"



  override def readCookie(request: RequestHeader): Option[Cookie] = {
    Logger.debug(s"Requesting cookie ${PublicSettings.assymCookieName}")
    Logger.debug(request.cookies.map(c=>c.name -> c.value).toMap.toString())
    request.cookies.get(PublicSettings.assymCookieName)
  }

  override def extractAuth(request: RequestHeader): AuthenticationStatus = {
    Logger.debug(s"readCookie result is ${readCookie(request)}")
    readCookie(request).map { cookie =>
      PanDomain.authStatus(cookie.value, panDomainSettings.settings.publicKey, validateUser) match {
        case Expired(authedUser) if authedUser.isInGracePeriod(apiGracePeriod) =>
          Logger.debug("auth expired, but in grace period")
          GracePeriod(authedUser)
        case authStatus @ Authenticated(authedUser) =>
          Logger.debug(s"authenticated user, cache validation set to $cacheValidation")
          if (cacheValidation && authedUser.authenticatedIn(panDomainSettings.system)) authStatus
          else if (validateUser(authedUser)){
            Logger.debug("authedUser validated")
            authStatus
          }
          else {
            Logger.debug("authedUser did not validate")
            NotAuthorized(authedUser)
          }
        case authStatus =>
          Logger.debug(s"Other auth status: ${authStatus.toString}")
          authStatus
      }
    } getOrElse NotAuthenticated
  }

  override def cacheValidation = false

  override def authCallbackUrl: String = config.get[String]("auth.deployedUrl") + "/oauthCallback"

  def userProfileFromSession(session:Session):Option[Either[io.circe.Error, UserProfile]] = {
    session.get("userProfile")
      .map(profileJson=>io.circe.parser.parse(profileJson).flatMap(_.as[UserProfile]))
  }

}