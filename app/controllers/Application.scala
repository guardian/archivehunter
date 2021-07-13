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

  def index(path:String) = IsAuthenticated { request=> uid=>
    Ok(views.html.index("Archive Hunter")("fake-cachebuster"))
  }

  /**
    * provides a standard html page behind google auth.  The frontend passes this to the panda-session library to refresh
    * credentials; the refresh is all done by IsAuthenticated, then the content is loaded into an invisible iframe which is deleted again.
    * @return
    */
  def authstub = IsAuthenticated { request=> uid=>
    Ok(views.html.authstub())
  }

  def healthcheck = Action {
    //basic healthcheck endpoint, will extend later
    Ok("online")
  }

  def test419 = IsAuthenticated { request=> uid=>
    new Status(419)
  }
}