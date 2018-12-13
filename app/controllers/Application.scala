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
  extends AbstractController(controllerComponents) with PanDomainAuthActions {


  def rootIndex() = index("")

  def index(path:String) = AuthAction {
    Ok(views.html.index("Archive Hunter")("fake-cachebuster"))
  }

  def healthcheck = Action {
    //basic healthcheck endpoint, will extend later
    Ok("online")
  }
}