package controllers

import com.theguardian.multimedia.archivehunter.common.ProxyTypeEncoder
import helpers.InjectableRefresher
import javax.inject.Inject
import models.{KnownFileExtension, KnownFileExtensionDAO}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.libs.ws.WSClient
import play.api.mvc.{AbstractController, ControllerComponents}
import io.circe.generic.auto._
import io.circe.syntax._
import responses.{GenericErrorResponse, ObjectCreatedResponse, ObjectListResponse}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class KnownFileExtensionController @Inject() (override val config:Configuration,
                                              override val controllerComponents:ControllerComponents,
                                              override val refresher:InjectableRefresher,
                                              override val wsClient:WSClient,
                                              fileExtensionDAO:KnownFileExtensionDAO)
  extends AbstractController(controllerComponents) with Circe with ProxyTypeEncoder with PanDomainAuthActions
{
  private val logger = Logger(getClass)

  def putRecord = APIAuthAction.async(circe.json(2048)) {request=>
    request.body.as[KnownFileExtension].fold(
      failure=>Future(BadRequest(GenericErrorResponse("bad_request", failure.toString()).asJson)),
      newXtn=>fileExtensionDAO.put(newXtn).map({
        case None=>Ok(ObjectCreatedResponse("ok","knownExtension",newXtn.extension).asJson)
        case Some(Right(_))=>Ok(ObjectCreatedResponse("ok","knownExtension",newXtn.extension).asJson)
        case Some(Left(err))=>
          logger.error(s"Could not write $newXtn to database: $err")
          InternalServerError(GenericErrorResponse("db_error",err.toString).asJson)
      })
    )
  }

  def getAll = APIAuthAction.async {
    fileExtensionDAO.getAll.map({
      case Left(errorList)=>
        logger.error(s"Could not retrieve file extensions list: $errorList")
        InternalServerError(GenericErrorResponse("db_error", errorList.map(_.toString).mkString(",")).asJson)
      case Right(resultList)=>
        Ok(ObjectListResponse("ok","knownExtension",resultList,resultList.length).asJson)
    })
  }
}
