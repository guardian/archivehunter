package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpMethods, HttpRequest, MediaRange, MediaTypes, ResponseEntity, StatusCodes}
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.ByteString
import auth.{BearerTokenAuth, LoginResultOK}
import com.nimbusds.jwt.JWTClaimsSet
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.mvc.{AbstractController, ControllerComponents, Cookie, Request, ResponseHeader, Result, Session}

import java.net.{URL, URLEncoder}
import java.nio.charset.StandardCharsets
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import io.circe.generic.auto._
import io.circe.syntax._
import models.{UserProfile, UserProfileDAO}
import play.api.libs.circe.Circe
import play.api.mvc.Cookie.SameSite
import responses.GenericErrorResponse
import auth.ClaimsSetExtensions._
import helpers.HttpClientFactory

import java.time.{Duration, Instant, ZonedDateTime}
import scala.util.Try

@Singleton
class Auth @Inject() (config:Configuration,
                      bearerTokenAuth: BearerTokenAuth,
                      userProfileDAO:UserProfileDAO,
                      cc:ControllerComponents,
                      httpFactory:HttpClientFactory)
                     (implicit actorSystem: ActorSystem)
  extends AbstractController(cc) with Circe {
  private implicit val ec:ExecutionContext = cc.executionContext
  private val logger = LoggerFactory.getLogger(getClass)
  import Auth._

  /**
    * allow overriding of the Http() object for testing
    * @return
    */
  protected def http = httpFactory.build

  //sometimes in development it's easier to run without https, this is indicated by the `enforceSecure` parameter in the config
  private def redirectProto = if(config.getOptional[Boolean]("oAuth.enforceSecure").getOrElse(true)) "https://" else "http://"
  def redirectUri[T](request:Request[T]) = redirectProto + request.host + "/oauthCallback"

  /**
    * builds a URL to the oauth IdP and redirects the user there
    * @return
    */
  def login(state:Option[String]) = Action { request=>
    val args = Map(
      "response_type"->"code",
      "client_id"->config.get[String]("oAuth.clientId"),
      "resource"->config.get[String]("oAuth.resource"),
      "redirect_uri"->redirectUri(request),
      "state"->state.getOrElse("/")
    )

    logger.debug(s"OAuth arguments before encoding: $args")
    val queryArgs = assembleFromMap(args)
    logger.debug(s"OAuth arguments after decoding: $queryArgs")

    val finalUrl = config.get[String]("oAuth.oAuthUri") + "?" + queryArgs
    TemporaryRedirect(finalUrl)
  }

  /**
    * internal method to take a Map of parameters and turn them into a urlencoded string
    * @param content a string->string map
    * @return a url-encoded string with all of the parameters from `content`
    */
  private def assembleFromMap(content:Map[String,String]) = content
    .map(kv=>s"${kv._1}=${URLEncoder.encode(kv._2, "UTF-8")}")
    .mkString("&")

  /**
    * internal method to return a cookie configured for authentication
    * as per the server config
    * @param name cookie name
    * @param value cookie value
    * @return the Cookie
    */
  private def makeAuthCookie(name:String, value:String):Cookie =
    Cookie(
      name,
      value,
      maxAge = Some(3600*8),    //expires in 8 hours, FIXME should be configurable
      secure = config.getOptional[Boolean]("oAuth.enforceSecure").getOrElse(true),
      httpOnly=true,
      sameSite = Some(SameSite.Strict)
    )

  /**
    * internal method, part of the step two exchange.
    * Given a decoded JWT, try to look up the user's profile in the database.
    * If there is no profile existing at the moment then create a base one.
    * @param response result from `validateContent`
    * @return a Future with a Left if an error occurred (with descriptive string) and a Right if we got the UserProfile
    */
  private def userProfileFromJWT(response: Either[String, JWTClaimsSet]) = {
    response match {
      case Left(err)=>Future(Left(err))
      case Right(oAuthResponse)=>
        userProfileDAO.userProfileForEmail(oAuthResponse.getUserID).flatMap({
          case None=>
            logger.info(s"No user profile existing for ${oAuthResponse.getUserID}, creating one")
            val newUserProfile = UserProfile(
              oAuthResponse.getUserID,
              oAuthResponse.getIsMMAdmin,
              Seq(),
              allCollectionsVisible=true,
              None,
              None,
              None,
              None,
              None,
              None
            )
            userProfileDAO
              .put(newUserProfile)
              .map({
                case None=>
                  logger.debug("userProfileDAO.put returned None, assuming saved")
                  Right(newUserProfile)
                case Some(Right(savedProfile))=>
                  logger.debug("userProfileDAO.put returned a profile, assuming that is the one that was saved")
                  Right(savedProfile)
                case Some(Left(err))=>
                  Left(err.toString)
              })
          case Some(Left(dynamoErr))=>Future(Left(dynamoErr.toString))
          case Some(Right(userProfile))=>Future(Right(userProfile))
        })
    }
  }

  /**
    * internal method, part of the step two exchange.
    * Given the response from the server, validate and decode the JWT present
    * @param response result from `stageTwo`
    * @return a Future with either a Left with descriptive error string or Right with the decoded claims set
    */
  private def validateContent(response: Either[String, OAuthResponse]) = Future(
    response
      .flatMap(oAuthResponse=>{
        bearerTokenAuth
          .validateToken(LoginResultOK(oAuthResponse.access_token.get)) match {
            case Left(err)=>Left(err.toString)
            case Right(response)=>Right(response.content)
          }
      })
  )

  /**
    * internal method, part of the step two exchange.
    *
    * Given the response from the server and the response from the user profile, either of which could be errors,
    * formulate a response for the client
    * @param maybeOAuthResponse result from `stageTwo`
    * @param maybeUserProfile result from `userProfileFromJWT`
    * @param header ResponseHeader entitiy that is sent to the client on success
    * @param entity HttpEntitiy indicating the body of the response that is sent to the client on success
    * @return a Future containing a Play response
    */
  private def finalCallbackResponse(maybeOAuthResponse:Either[String, OAuthResponse],
                                    maybeUserProfile:Either[String, UserProfile],
                                    header:ResponseHeader,
                                    entity: play.api.http.HttpEntity) = Future(
    maybeOAuthResponse match {
      case Left(err)=>
        logger.error(s"Could not perform oauth exchange: $err")
        InternalServerError(GenericErrorResponse("error",err).asJson)
      case Right(oAuthResponse)=>
        logger.debug(s"oauth exchange successful, got $oAuthResponse")

        val baseSessionValues = Map[String,String]()

        val sessionValues = maybeUserProfile match {
          case Left(err)=>
            logger.warn(s"Could not load user profile: $err")
            baseSessionValues
          case Right(profile)=>
            baseSessionValues ++ Map("userProfile"->profile.asJson.noSpaces)
        }

        val cookies = Map(
          config.get[String]("oAuth.authCookieName") -> oAuthResponse.access_token,
          config.get[String]("oAuth.refreshCookieName") -> oAuthResponse.refresh_token
        )
          .map(kv=>kv._2.map(makeAuthCookie(kv._1, _)))
          .collect({case Some(value)=>value})

        Result(
          header,
          entity,
          newCookies = cookies.toList
        ).withSession(Session(sessionValues))
    }
  )

  def oauthCallback(state:Option[String], code:Option[String], error:Option[String]) = Action.async { request=>
    (code, error) match {
      case (Some(actualCode), _)=>
        for {
          maybeOauthResponse    <- stageTwo(actualCode, redirectUri(request))
          maybeValidatedContent <- validateContent(maybeOauthResponse)
          maybeUserProfile      <- userProfileFromJWT(maybeValidatedContent)
          result                <- finalCallbackResponse(maybeOauthResponse,
                                      maybeUserProfile,
                                      ResponseHeader(StatusCodes.TemporaryRedirect.intValue, headers=Map("Location"->state.getOrElse("/"))),
                                      play.api.http.HttpEntity.NoEntity
                                  )
        } yield result

      case (_, Some(error))=>
        Future(InternalServerError(s"Auth provider could not log you in: $error.  Try refreshing the page."))
      case (None,None)=>
        Future(InternalServerError("Invalid response from auth provider. Try refreshing the page."))
    }
  }

  /**
    * internal method to read in the content of the ResponseEntity and parse it as JSON
    * @param body the ResponseEntity
    * @tparam T   data type to unmarshal the response into. A Left is returned if this unmarshalling fails.
    * @return a Future containing either a parser/decoder error or an OAuthResponse model
    */
  def consumeBody[T:io.circe.Decoder](body:ResponseEntity):Future[Either[io.circe.Error, T]] = {
    body.dataBytes
      .map(_.decodeString(StandardCharsets.UTF_8))
      .toMat(Sink.reduce[String](_ + _))(Keep.right)
      .run()
      .map(content=>{
        logger.debug(s"raw auth content is $content")
        content
      })
      .map(io.circe.parser.parse)
      .map(_.flatMap(_.as[T]))
  }

  protected def stageTwo(code:String, redirectUri:String) = {
    val postdata = Map(
      "grant_type"->"authorization_code",
      "client_id"->config.get[String]("oAuth.clientId"),
      "redirect_uri"->redirectUri,
      "code"->code
    )

    val contentBody = HttpEntity(ContentType(MediaTypes.`application/x-www-form-urlencoded`) ,assembleFromMap(postdata))

    val headers = List(
      Accept(MediaRange(MediaTypes.`application/json`))
    )

    logger.debug(s"oauth step2 exchange server url is ${config.get[String]("oAuth.tokenUrl")} and unformatted request content is $postdata")
    val rq = HttpRequest(HttpMethods.POST, uri=config.get[String]("oAuth.tokenUrl"), headers=headers, entity=contentBody)

    ( for {
      response <- http.singleRequest(rq)
      bodyContent <- consumeBody[OAuthResponse](response.entity)
      } yield (response, bodyContent)
    ).map({
      case (response, Right(oAuthResponse))=>
        if(response.status==StatusCodes.OK) {
          Right(oAuthResponse)
        } else {
          Left(s"Server responded with an error ${response.status} ${oAuthResponse.toString}")
        }
      case (_, Left(decodingError))=>
        Left(s"Could not decode response from oauth server: $decodingError")
    })

  }

  def logout() = Action {
    TemporaryRedirect("/").withSession(Session.emptyCookie)
  }

  private def safeGetCookie[A](request:Request[A], configPathToName:String):Option[Cookie] = Try {
    request.cookies.get(config.get[String](configPathToName))
  }.toOption.flatten

  protected def requestRefresh(refreshToken:String) = {
    val params = Map(
      "grant_type"->"refresh_token",
      "refresh_token"->refreshToken
    )
    val encodedParams = assembleFromMap(params)
    val contentBody = HttpEntity(ContentTypes.`application/x-www-form-urlencoded`, encodedParams)
    val headers = scala.collection.immutable.Seq(
      Accept(MediaRange(MediaTypes.`application/json`))
    )
    val req = HttpRequest(HttpMethods.POST, config.get[String]("oAuth.tokenUrl"), headers, contentBody)

    (for {
      response <- http.singleRequest(req)
      responseBody <- consumeBody[OAuthResponse](response.entity)
    } yield (response, responseBody) ).map({
      case (response, Right(oAuthResponse))=>
        response.status match {
          case StatusCodes.OK=>
            Right(oAuthResponse)
          case StatusCodes.BadGateway | StatusCodes.ServiceUnavailable=>
            Left("Authorization server is not available at the moment, hopefully refresh will work next time")
          case _=>
            Left(s"Server returned ${response.status}")
        }
      case (response, Left(err))=>
        logger.error(s"Could not parse response from server: $err")
        response.status match {
          case StatusCodes.BadGateway | StatusCodes.ServiceUnavailable=>
            Left("Authorization server is not available at the moment, hopefully refresh will work next time")
          case StatusCodes.BadRequest=>
            Left("Internal error, server rejected our request")
          case StatusCodes.InternalServerError=>
            Left("Authorization server failed trying to process our request, contact Infrastructure")
          case _=>
            Left(s"Server returned ${response.status}")
        }
    })

  }

  def refreshIfRequired = Action.async { request =>
    (safeGetCookie(request, "oAuth.authCookieName"), safeGetCookie(request, "oAuth.refreshCookieName")) match {
      case (Some(authCookie), Some(refreshCookie)) =>
        bearerTokenAuth.validateToken(LoginResultOK(authCookie.value)) match {
          case Left(loginProblem) =>
            logger.error(s"Invalid access token presented for refresh: $loginProblem")
            Future(BadRequest(GenericErrorResponse("error","access token not valid").asJson))
          case Right(LoginResultOK(claimsSet))=>
            if(Auth.claimIsExpired(claimsSet)) {
              for {
                maybeOauthResponse <- requestRefresh(refreshCookie.value)
                maybeValidatedContent <- validateContent(maybeOauthResponse)
                maybeUserProfile <- userProfileFromJWT(maybeValidatedContent)
                result <- finalCallbackResponse(maybeOauthResponse,
                  maybeUserProfile,
                  ResponseHeader(200),
                  play.api.http.HttpEntity.Strict(
                    ByteString(GenericErrorResponse("ok","token refreshed").asJson.noSpaces),
                    Some("application/json")
                  )
                )
              } yield result
            } else {
              logger.info(s"No token refresh required for ${claimsSet.getUserID}")
              Future(Ok(GenericErrorResponse("not_needed","no refresh required").asJson))
            }
        }
      case (_, None) =>
        logger.error("Could not refresh login, either there was no refresh cookie or `oAuth.refreshCookieName` is not set in the config")
        Future(BadRequest(GenericErrorResponse("error", "either no refresh cookie or server misconfigured").asJson))
      case (None, _) =>
        logger.error("Could not refresh login because no access cookie was presented, not evan an expired one")
        Future(BadRequest(GenericErrorResponse("error", "An access token must be presented even if it's expired").asJson))
    }
  }
}

object Auth {
  private val logger = LoggerFactory.getLogger(getClass)

  case class OAuthResponse(access_token:Option[String], refresh_token:Option[String], error:Option[String])

  /**
    * returns a booolean indicating if the given claims set either has expired or is about to
    * @param claimsSet JWTClaimsSet from the given token
    * @return true if the claims set is expired or shortly will be
    */
  def claimIsExpired(claimsSet:JWTClaimsSet) = {
    val expiryWindow = Duration.ofMinutes(2)  //attempt a refresh if the token is valid for less than this
    val expiresIn = Duration.between(Instant.now(), claimsSet.getExpirationTime.toInstant)
    logger.debug(s"${claimsSet.getUserID} refresh check - access token expiry at ${claimsSet.getExpirationTime} which expires in $expiresIn")
    expiresIn.isNegative||expiresIn.isZero||expiryWindow.compareTo(expiresIn)>=0  //compareTo - if window>expiresIn result =1, if == result=0 if < result=-1
  }
}