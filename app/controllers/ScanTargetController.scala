package controllers

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo._
import com.gu.scanamo.syntax._
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import helpers.DynamoClientManager
import javax.inject.Inject
import play.api.Configuration
import play.api.mvc.{AbstractController, ControllerComponents}
import io.circe.generic.auto._
import io.circe.syntax._
import models.ScanTarget
import play.api.libs.circe.Circe
import responses.{GenericErrorResponse, ObjectCreatedResponse}
import akka.stream.alpakka.dynamodb.scaladsl._
import akka.stream.alpakka.dynamodb.impl._
import com.amazonaws.auth.{AWSStaticCredentialsProvider, InstanceProfileCredentialsProvider}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ScanTargetController @Inject() (config:Configuration,cc:ControllerComponents,ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem) extends AbstractController(cc) with Circe with ZonedDateTimeEncoder {
  implicit val zonedTimeFormat = DynamoFormat.coercedXmap[ZonedDateTime, String, IllegalArgumentException](
    ZonedDateTime.parse(_)
  )(
    _.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
  )

  implicit val mat:Materializer = ActorMaterializer.create(system)

  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))

  def newTarget = Action(circe.json[ScanTarget]) { scanTarget=>
    Scanamo.exec(ddbClientMgr.getNewDynamoClient())(table.put(scanTarget.body)).map({
      case Left(writeError)=>
        InternalServerError(GenericErrorResponse("error",writeError.toString).asJson)
      case Right(createdScanTarget)=>
        Ok(ObjectCreatedResponse[String]("created","scan_target",createdScanTarget.bucketName).asJson)
    }).getOrElse(InternalServerError(GenericErrorResponse("error","Nothing was created").asJson))
  }

  def removeTarget(targetName:String) = Action {
    val r = Scanamo.exec(ddbClientMgr.getNewDynamoClient())(table.delete('bucketName -> targetName))
    Ok(ObjectCreatedResponse[String]("deleted","scan_target",targetName).asJson)
  }

  def listScanTargets = Action.async {
    val alpakkaClient = ddbClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

    ScanamoAlpakka.exec(alpakkaClient.asInstanceOf[akka.stream.alpakka.dynamodb.scaladsl.DynamoClient])(table.scan()).map({ result=>
      val errors = result.collect({
        case Left(readError)=>readError
      })

      if(errors.isEmpty){
        val success = result.collect({
          case Right(scanTarget)=>scanTarget
        })
        Ok(ObjectCreatedResponse[List[ScanTarget]]("ok","scan_target",success).asJson)
      } else {
        InternalServerError(GenericErrorResponse("error", errors.map(_.toString).mkString(",")).asJson)
      }
    })

  }
}
