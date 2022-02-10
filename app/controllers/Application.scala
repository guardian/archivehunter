package controllers

import auth.{BearerTokenAuth, Security}

import javax.inject.{Inject, Singleton}
import play.api._
import play.api.cache.SyncCacheApi
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc._
import responses.GenericErrorResponse
import io.circe.syntax._
import io.circe.generic.auto._
import java.time.{Duration, Instant}
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class Application @Inject() (override val controllerComponents:ControllerComponents,
                             override val bearerTokenAuth: BearerTokenAuth,
                             override val cache:SyncCacheApi,
                             override val config: Configuration)
  extends AbstractController(controllerComponents) with Security with Circe {


  def rootIndex() = index("")

  def index(path:String) = Action { request=>
    Ok(views.html.index("Archive Hunter")("fake-cachebuster"))
  }

  def healthcheck = Action {
    //basic healthcheck endpoint, will extend later
    Ok("online")
  }

  def test419 = IsAuthenticated { request=> uid=>
    new Status(419)
  }

}