package controllers

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}

import io.circe.generic.auto._
import io.circe.syntax._
import models.{JobReport, JobReportError, JobReportSuccess}
import responses.GenericErrorResponse

import scala.util.{Failure, Success}

@Singleton
class JobController @Inject() (config:Configuration, cc:ControllerComponents) extends AbstractController(cc) with Circe{
  private val logger = Logger(getClass)

  def updateStatus(jobId:String) = Action(circe.json(2048)) { request=>
    JobReport.getResult(request.body) match {
      case None=>
        BadRequest(GenericErrorResponse("error","Could not decode any job report from input").asJson)
      case Some(result)=>result match {
        case report:JobReportError=>
          report.decodeLog match {
            case Success(decodedReport)=>
              logger.error(s"Outboard process indicated job failure (successfully decoded): $decodedReport")
              Ok(GenericErrorResponse("ok","received report").asJson)
            case Failure(err)=>
              logger.warn(s"Could not decode report: ", err)
              logger.error(s"Outboard process indicated job failure: $report")
              Ok(GenericErrorResponse("ok","received report").asJson)
          }
        case report:JobReportSuccess=>
          logger.info(s"Outboard process indicated job success: $report")
          Ok(GenericErrorResponse("ok","received report").asJson)
      }
    }
  }

}
