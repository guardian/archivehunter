/*
 * Copyright (C) 2015 Jason Mar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Andy Gallagher to provide extra IsAuthenticated implementations for async actions etc.
 */

package auth

import com.nimbusds.jwt.JWTClaimsSet
import models.{UserProfile, UserProfileDAO}
import org.slf4j.LoggerFactory
import play.api.mvc._
import play.api.{ConfigLoader, Configuration, Logger}
import play.api.cache.SyncCacheApi
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.typedmap.TypedKey
import io.circe.generic.auto._
import io.circe.syntax._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

sealed trait LoginResult

final case class LoginResultOK[A](content:A) extends LoginResult
final case class LoginResultInvalid[A](content:A) extends LoginResult
final case class LoginResultExpired[A](content:A) extends LoginResult
final case class LoginResultMisconfigured[A](content:A) extends LoginResult
case object LoginResultNotPresent extends LoginResult

class UserRequest[A](val user: User, request: Request[A]) extends WrappedRequest[A](request)

object Security {
  /**
   * this is a copy of the regular Security.Authenticated method from Play, adjusted to use an Either instead of an
   * Option so we can pass on information about why a login failed
   * @param userinfo a function that takes the request header and must return either a Left with a LoginResult
   *                 indicating failure or Right with a LoginResult indicating success
   * @param onUnauthorized a function that takes the request header and the login status returned by `userinfo`,
   *                       if it was a failure. It must return a Play response that will get returned to the client.
   * @param action the play action being wrapped
   * @tparam A the type of data that the LoginResult will contain
   * @return the wrapped Play action
   */
  def MyAuthenticated[A](
                          userinfo: RequestHeader => Either[LoginResult, LoginResultOK[A]],
                          onUnauthorized: (RequestHeader, LoginResult) => Result
                        )(action: A => EssentialAction): EssentialAction = {
    EssentialAction { request =>
      userinfo(request) match {
        case Right(result) =>
          action(result.content)(request)
        case Left(loginProblem) =>
          Accumulator.done(onUnauthorized(request, loginProblem))
      }
    }
  }
}

trait Security extends BaseController {
  implicit val cache:SyncCacheApi
  val bearerTokenAuth:BearerTokenAuth //this needs to be injected from the user

  protected val logger: org.slf4j.Logger = LoggerFactory.getLogger(getClass)
  implicit val config:Configuration

  /**
   * look up an hmac user
   * @param header HTTP request object
   * @param auth Authorization token as passed from the client
   */
  private def hmacUsername(header: RequestHeader, auth: String):Either[LoginResult, LoginResultOK[JWTClaimsSet]] = {
    logger.debug(s"headers: ${header.headers.toSimpleMap.toString}")
    if(Conf.sharedSecret.isEmpty){
      logger.error("Unable to process server->server request, shared_secret is not set in application.conf")
      Left(LoginResultMisconfigured(auth))
    } else {
      val claimBuilder = new JWTClaimsSet.Builder
      val hmacClaim = claimBuilder
        .issuer("hmac")
        .audience("archivehunter")
        .subject(auth)
        .build()
      HMAC
        .calculateHmac(header, Conf.sharedSecret)
        .map(calculatedSig => {
          if ("HMAC "+calculatedSig == auth) Right(LoginResultOK(hmacClaim)) else Left(LoginResultInvalid("hmac"))
        })
        .getOrElse(Left(LoginResultInvalid("")))
    }
  }

  object AuthType extends Enumeration {
    val AuthHmac, AuthJWT, AuthSession = Value
  }
  final val AuthTypeKey = TypedKey[AuthType.Value]("auth_type")

  //if this returns something, then we are logged in
  private def username(request:RequestHeader):Either[LoginResult, LoginResultOK[JWTClaimsSet]] =
    (request.headers.get("Authorization"), request.session.get("claims")) match {
      case (Some(auth), _)=>
        if(auth.contains("HMAC")) {
          logger.debug("got HMAC Authorization header, doing hmac auth")
          val updatedRequest = request.addAttr(AuthTypeKey, AuthType.AuthHmac)
          hmacUsername(updatedRequest, auth)
        } else {
          logger.warn("got request with Authorization header that is not HMAC")
          Left(LoginResultNotPresent)
        }
      case (None, Some(claims))=>
        io.circe.parser.parse(claims).flatMap(_.as[Map[String,String]]) match {
          case Right(json)=>
            val claims = json
              .foldLeft(new JWTClaimsSet.Builder())((builder, kv)=>builder.claim(kv._1, kv._2))
              .build()
            logger.debug(s"reconstituted claims: ${claims}")
            Right(LoginResultOK(claims))
          case Left(err)=>
            Left(LoginResultInvalid(err.toString))
        }
      case (None, None)=>
        bearerTokenAuth(request).map(result => {
          LoginResultOK(result.content)
        })
    }

  private def onUnauthorized(request: RequestHeader, loginResult: LoginResult) = loginResult match {
    case LoginResultInvalid(detailString:String)=>
      Results.Forbidden(Json.obj("status"->"error","detail"->detailString))
    case LoginResultInvalid(_)=>
      Results.Forbidden(Json.obj("status"->"error", "detail"->"Unknown error"))
    case LoginResultExpired(user:String)=>
      Results.Unauthorized(Json.obj("status"->"expired","detail"->"Your login has expired","username"->user))
    case LoginResultExpired(_)=>  //this shouldn't happen, but it keeps the compiler happy
      Results.Unauthorized(Json.obj("status"->"expired"))
    case LoginResultMisconfigured(_)=>
      Results.InternalServerError(Json.obj("status"->"error","detail"->"Server configuration error, please check the logs"))
    case LoginResultNotPresent=>
      Results.Forbidden(Json.obj("status"->"error","detail"->"No credentials provided"))
    case LoginResultOK(user)=>
      logger.error(s"LoginResultOK passed to onUnauthorized! This must be a bug. Username is $user.")
      Results.InternalServerError(Json.obj("status"->"logic_error","detail"->"Login should have succeeded but error handler called. This is a server bug."))
  }

  def IsAuthenticated(f: => JWTClaimsSet => Request[AnyContent] => Result) = Security.MyAuthenticated(username, onUnauthorized) {
    uid => Action(request => f(uid)(request))
  }

  def IsAuthenticatedAsync(f: => JWTClaimsSet => Request[AnyContent] => Future[Result]) = Security.MyAuthenticated(username, onUnauthorized) {
    uid => Action.async(request => f(uid)(request))
  }

  def IsAuthenticatedAsync[A](b: BodyParser[A])(f: => JWTClaimsSet => Request[A] => Future[Result]) = Security.MyAuthenticated(username, onUnauthorized) {
    uid=> Action.async(b)(request => f(uid)(request))
  }

  def IsAuthenticated[A](b: BodyParser[A])(f: => JWTClaimsSet => Request[A] => Result) = Security.MyAuthenticated(username, onUnauthorized) {
    uid => Action(b)(request => f(uid)(request))
  }

  def checkAdmin[A](claims:JWTClaimsSet, request:Request[A]) = {
      val adminClaimContent = for {
        maybeAdminClaim <- Option(claims.getStringClaim(bearerTokenAuth.isAdminClaimName())) match {
          case Some(str)=>Right(LoginResultOK(str))
          case None=>Left(LoginResultNotPresent)
        }
      } yield maybeAdminClaim

      adminClaimContent match {
        case Right(LoginResultOK(stringValue))=>
          logger.debug(s"got value for isAdminClaim ${bearerTokenAuth.isAdminClaimName()}: $stringValue, downcasing and testing for 'true' or 'yes'")
          val downcased = stringValue.toLowerCase()
          downcased == "true" || downcased == "yes"
        case Left(_)=>
          logger.debug(s"got nothing for isAdminClaim ${bearerTokenAuth.isAdminClaimName()}")
          false
      }
  }

  /**
   * determine if the given user is an admin.  This implies an IsAuthenticated check.
   * if the X-Hmac-Authorization header is present, then the request is server-server and the user is not an admin
   * if the Authoriztion header is present, then the request is a bearer token. The user is considered an admin
   * if a string claim with the name given by the config key auth.adminClaim is present and has a value of either "true" or "yes"
   * if neither is present, then the request is a session-auth request and a check is made to the remote LDAP server for
   * group membership
   * @param f the action function
   * @return the result of the action function or Forbidden
   */
  def IsAdmin(f: => JWTClaimsSet => Request[AnyContent] => Result) = IsAuthenticated { uid=> request=>
    if(checkAdmin(uid, request)){
      f(uid)(request)
    } else {
      logger.warn(s"Admin request rejected for $uid to ${request.uri}")
      Forbidden(Json.obj("status"->"forbidden","detail"->"You need admin rights to perform this action"))
    }
  }

  def IsAdmin[A](b: BodyParser[A])(f: => JWTClaimsSet => Request[A] => Result) = IsAuthenticated(b) { uid=> request=>
    if(checkAdmin(uid, request)){
      f(uid)(request)
    } else {
      logger.warn(s"Admin request rejected for $uid to ${request.uri}")
      Forbidden(Json.obj("status"->"forbidden","detail"->"You need admin rights to perform this action"))
    }
  }

  def IsAdminAsync[A](b: BodyParser[A])(f: => JWTClaimsSet => Request[A] => Future[Result]) = IsAuthenticatedAsync(b) { uid=> request=>
    if(checkAdmin(uid,request)) {
      f(uid)(request)
    } else {
      logger.warn(s"Admin request rejected for $uid to ${request.uri}")
      Future(Forbidden(Json.obj("status"->"forbidden","detail"->"You need admin rights to perform this action")))
    }
  }

  def IsAdminAsync(f: => JWTClaimsSet => Request[AnyContent] => Future[Result]) = IsAuthenticatedAsync { uid=> request=>
    if(checkAdmin(uid,request)) {
      f(uid)(request)
    } else {
      logger.warn(s"Admin request rejected for $uid to ${request.uri}")
      Future(Forbidden(Json.obj("status"->"forbidden","detail"->"You need admin rights to perform this action")))
    }
  }

  def userProfileFromSession(session:Session):Option[Either[io.circe.Error, UserProfile]] = {
    import io.circe.generic.auto._
    session.get("userProfile")
      .map(profileJson=>io.circe.parser.parse(profileJson).flatMap(_.as[UserProfile]))
  }

  def targetUserProfile[T](request:Request[T], targetUser:String)(implicit userProfileDAO:UserProfileDAO) = {
    if(targetUser=="my"){
      Future(userProfileFromSession(request.session))
    } else {
      userProfileFromSession(request.session) match {
        case Some(Left(err))=>
          Future(Some(Left(err)))
        case Some(Right(someUserProfile))=>
          if(!someUserProfile.isAdmin){
            logger.error(s"Non-admin user is trying to access lightbox of $targetUser")
            Future(None)
          } else {
            userProfileDAO.userProfileForEmail(targetUser)
          }
        case None=>
          Future(None)
      }
    }
  }
}
