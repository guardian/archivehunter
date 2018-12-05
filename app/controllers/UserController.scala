package controllers

import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import helpers.InjectableRefresher
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}

@Singleton
class UserController @Inject()(override val controllerComponents:ControllerComponents,
                               override val wsClient: WSClient,
                               override val config: Configuration,
                               override val refresher:InjectableRefresher)
  extends AbstractController(controllerComponents) with PanDomainAuthActions  with Circe {

  def loginStatus = AuthAction { request =>
    val user = request.user
    Ok(user.toJson)
  }
}
