package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.headers.{Accept, `Content-Type`}
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpHeader, HttpMethods, HttpRequest, MediaRange, MediaTypes, ResponseEntity, StatusCode, StatusCodes}
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.ByteString
import auth.BearerTokenAuth
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.mvc.{AbstractController, ControllerComponents, Cookie, Request, ResponseHeader, Result}

import java.net.{URL, URLEncoder}
import java.nio.charset.StandardCharsets
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import io.circe.generic.auto._
import io.circe.syntax._
import play.api.libs.circe.Circe
import play.api.mvc.Cookie.SameSite
import responses.GenericErrorResponse

import javax.net.ssl.{SSLContext, SSLEngine}

@Singleton
class Auth @Inject() (config:Configuration, bearerTokenAuth: BearerTokenAuth, cc:ControllerComponents)(implicit actorSystem: ActorSystem)
  extends AbstractController(cc) with Circe {
  private implicit val ec:ExecutionContext = cc.executionContext
  private val logger = LoggerFactory.getLogger(getClass)

  case class OAuthResponse(access_token:Option[String], refresh_token:Option[String], error:Option[String])

  def redirectUri[T](request:Request[T]) = "http://" + request.host + "/oauthCallback"
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

  private def assembleFromMap(content:Map[String,String]) = content
    .map(kv=>s"${kv._1}=${URLEncoder.encode(kv._2, "UTF-8")}")
    .mkString("&")

  private def makeAuthCookie(name:String, value:String):Cookie =
    Cookie(
      name,
      value,
      maxAge = Some(3600*8),    //expires in 8 hours, FIXME should be configurable
      secure = config.getOptional[Boolean]("oAuth.enforceSecure").getOrElse(true),
      httpOnly=true,
      sameSite = Some(SameSite.Strict)
    )

  //http://localhost:9000/oauthCallback?
  //state=%2F
  //&session_state=5b357bd1-12cd-45e5-a7bd-d4fb5cb9b569
  //&code=0e3c49a4-be88-4927-9fb3-1432f8d2f0b2.5b357bd1-12cd-45e5-a7bd-d4fb5cb9b569.1d09d4cb-5a56-4cce-8e02-8ce08d033637
  def oauthCallback(state:Option[String], code:Option[String], error:Option[String]) = Action.async { request=>
    (code, error) match {
      case (Some(actualCode), _)=>
        stageTwo(actualCode, redirectUri(request))
          .map({
            case Left(err)=>
              logger.error(s"Could not perform oauth exchange: $err")
              InternalServerError(GenericErrorResponse("error",err).asJson)
            case Right(oAuthResponse)=>
              logger.debug(s"oauth exchange successful, got $oAuthResponse")
              val cookies = Map(
                config.get[String]("oAuth.authCookieName") -> oAuthResponse.access_token,
                config.get[String]("oAuth.refreshCookieName") -> oAuthResponse.refresh_token
              )
                .map(kv=>kv._2.map(makeAuthCookie(kv._1, _)))
                .collect({case Some(value)=>value})

              Result(
                ResponseHeader(StatusCodes.TemporaryRedirect.intValue, headers=Map("Location"->state.getOrElse("/"))),
                play.api.http.HttpEntity.NoEntity,
                newCookies = cookies.toList
              )
          })
      case (_, Some(error))=>
        Future(InternalServerError(s"Auth provider could not log you in: $error.  Try refreshing the page."))
      case (None,None)=>
        Future(InternalServerError("Invalid response from auth provider. Try refreshing the page."))
    }
  }

  def consumeBody(body:ResponseEntity):Future[Either[io.circe.Error, OAuthResponse]] = {
    body.dataBytes
      .map(_.decodeString(StandardCharsets.UTF_8))
      .toMat(Sink.reduce[String](_ + _))(Keep.right)
      .run()
      .map(content=>{
        logger.debug(s"raw auth content is $content")
        content
      })
      .map(io.circe.parser.parse)
      .map(_.flatMap(_.as[OAuthResponse]))
  }

  def stageTwo(code:String, redirectUri:String) = {
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
      response <- Http().singleRequest(rq)
      bodyContent <- consumeBody(response.entity)
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
    BadRequest("not implemented yet")
  }
}
