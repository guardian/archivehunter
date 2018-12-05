package controllers

import com.gu.pandomainauth.{PanDomain, PanDomainAuthSettingsRefresher, PublicSettings}
import com.gu.pandomainauth.action.AuthActions
import com.gu.pandomainauth.model._
import com.gu.pandomainauth.service.{CookieUtils, GoogleAuthException}
import helpers.InjectableRefresher
import play.api.mvc.{ControllerComponents, Cookie, RequestHeader}
import play.api.Configuration
import play.api.Logger
import play.api.mvc.Results.Redirect

trait PanDomainAuthActions extends AuthActions {
  val refresher:InjectableRefresher
  def config: Configuration
  def controllerComponents: ControllerComponents

  override def panDomainSettings: PanDomainAuthSettingsRefresher = refresher.panDomainSettings

  override def validateUser(authedUser: AuthenticatedUser): Boolean = {
    println(s"validateUser: $authedUser")
    (authedUser.user.email endsWith ("@guardian.co.uk"))
  }


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

  override def processGoogleCallback()(implicit request: RequestHeader) = {
    Logger.debug("processGoogleCallback")
    Logger.debug(request.session.toString)
    implicit val myExecContext = controllerComponents.executionContext
    val token =
      request.session.get(ANTI_FORGERY_KEY).getOrElse(throw new GoogleAuthException("missing anti forgery token"))
    val originalUrl =
      request.session.get(LOGIN_ORIGIN_KEY).getOrElse(throw new GoogleAuthException("missing original url"))

    val existingCookie = readCookie(request) // will be populated if this was a re-auth for expired login

    GoogleAuth.validatedUserIdentity(token)(request, controllerComponents.executionContext, wsClient).map { claimedAuth =>
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
        Logger.debug(s"Redirecting back to $originalUrl")
        Redirect(originalUrl)
          .withCookies(updatedCookies: _*)
          .withSession(session = request.session - ANTI_FORGERY_KEY - LOGIN_ORIGIN_KEY)
      } else {
        Logger.error(s"User did not auth")
        showUnauthedMessage(invalidUserMessage(claimedAuth))
      }
    }
  }

  override def cacheValidation = false

  override def authCallbackUrl: String = config.get[String]("auth.deployedUrl") + "/oauthCallback"
}