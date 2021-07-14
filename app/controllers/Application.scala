package controllers

import auth.{BearerTokenAuth, Security}

import javax.inject.{Inject, Singleton}
import play.api._
import play.api.cache.SyncCacheApi
import play.api.libs.ws.WSClient
import play.api.mvc._

@Singleton
class Application @Inject() (override val controllerComponents:ControllerComponents,
                             override val bearerTokenAuth: BearerTokenAuth,
                             override val cache:SyncCacheApi,
                             override val config: Configuration)
  extends AbstractController(controllerComponents) with Security  {


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