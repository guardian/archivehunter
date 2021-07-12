package controllers

import auth.{BearerTokenAuth, Security}
import io.circe.generic.auto._
import io.circe.syntax._

import javax.inject.{Inject, Singleton}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import responses.ObjectListResponse

@Singleton
class ConfigController @Inject()(override val config:Configuration,
                                 override val controllerComponents:ControllerComponents,
                                 override val bearerTokenAuth:BearerTokenAuth,
                                 override val cache:SyncCacheApi)
  extends AbstractController(controllerComponents) with Circe with Security {

  /**
    * Endpoint that returns configuration settings
    * @return
    */
  def getConfig = IsAuthenticated { uid=> request=>
    val defaultExpiry = config.getOptional[Int]("archive.restoresExpireAfter").getOrElse(3)
    val configSettings = List(defaultExpiry.toString)
    Ok(ObjectListResponse("ok","config. settings",configSettings,configSettings.length).asJson)
  }
}
