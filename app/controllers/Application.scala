package controllers

import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import helpers.InjectableRefresher
import javax.inject.Inject
import play.api._
import play.api.libs.ws.WSClient
import play.api.mvc._

class Application @Inject() (override val controllerComponents:ControllerComponents,
                             override val wsClient: WSClient,
                             override val config: Configuration,
                             override val refresher:InjectableRefresher)
  extends AbstractController(controllerComponents) with PanDomainAuthActions  {


  def rootIndex() = index("")

  def index(path:String) = AuthAction {
    Ok(views.html.index("Archive Hunter")("fake-cachebuster"))
  }

  /**
    * provides a standard html page behind google auth.  The frontend passes this to the panda-session library to refresh
    * credentials; the refresh is all done by AuthAction, then the content is loaded into an invisible iframe which is deleted again.
    * @return
    */
  def authstub = AuthAction {
    Ok(views.html.authstub())
  }

  def healthcheck = Action {
    //basic healthcheck endpoint, will extend later
    Ok("online")
  }

  def test419 = APIAuthAction {
    new Status(419)
  }
}