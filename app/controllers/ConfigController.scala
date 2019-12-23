package controllers

import helpers.InjectableRefresher
import io.circe.generic.auto._
import io.circe.syntax._
import javax.inject.{Inject, Singleton}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import play.api.{Configuration}
import responses.{ObjectListResponse}

@Singleton
class ConfigController @Inject()(override val config:Configuration,
                                 override val controllerComponents:ControllerComponents,
                                 override val refresher:InjectableRefresher,
                                 override val wsClient:WSClient)
  extends AbstractController(controllerComponents) with Circe with PanDomainAuthActions {

  /**
    * Endpoint that returns configuration settings
    * @return
    */
  def getConfig = APIAuthAction { request=>
    val defaultExpiry = config.getOptional[Int]("archive.restoresExpireAfter").getOrElse(3)
    val configSettings = List(defaultExpiry.toString)
    Ok(ObjectListResponse("ok","config. settings",configSettings,configSettings.length).asJson)
  }
}
