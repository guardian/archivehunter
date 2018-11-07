package controllers

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo._
import com.gu.scanamo.syntax._
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import helpers.{DynamoClientManager, ZonedTimeFormat}
import javax.inject.{Inject, Named}
import play.api.Configuration
import play.api.mvc._
import io.circe.generic.auto._
import io.circe.syntax._
import models.ScanTarget
import play.api.libs.circe.Circe
import responses.{GenericErrorResponse, ObjectCreatedResponse, ObjectGetResponse, ObjectListResponse}
import akka.stream.alpakka.dynamodb.scaladsl._
import akka.stream.alpakka.dynamodb.impl._
import com.amazonaws.auth.{AWSStaticCredentialsProvider, InstanceProfileCredentialsProvider}
import services.BucketScanner

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ScanTargetController @Inject() (@Named("bucketScannerActor") bucketScanner:ActorRef, config:Configuration,
                                      cc:ControllerComponents,ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem)
  extends AbstractController(cc) with Circe with ZonedDateTimeEncoder with ZonedTimeFormat {

  implicit val mat:Materializer = ActorMaterializer.create(system)

  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))

  private val profileName = config.getOptional[String]("externalData.awsProfile")

  def newTarget = Action(circe.json[ScanTarget]) { scanTarget=>
    Scanamo.exec(ddbClientMgr.getNewDynamoClient(profileName))(table.put(scanTarget.body)).map({
      case Left(writeError)=>
        InternalServerError(GenericErrorResponse("error",writeError.toString).asJson)
      case Right(createdScanTarget)=>
        Ok(ObjectCreatedResponse[String]("created","scan_target",createdScanTarget.bucketName).asJson)
    }).getOrElse(Ok(ObjectCreatedResponse[Option[String]]("created","scan_target",None).asJson))
  }

  def removeTarget(targetName:String) = Action {
    val r = Scanamo.exec(ddbClientMgr.getNewDynamoClient(profileName))(table.delete('bucketName -> targetName))
    Ok(ObjectCreatedResponse[String]("deleted","scan_target",targetName).asJson)
  }

  def get(targetName:String) = Action {
    Scanamo.exec(ddbClientMgr.getNewDynamoClient(profileName))(table.get('bucketName -> targetName)).map({
      case Left(err)=>
        InternalServerError(GenericErrorResponse("database_error", err.toString).asJson)
      case Right(result)=>
        Ok(ObjectGetResponse[ScanTarget]("ok", "scan_target", result).asJson)
    }).getOrElse(NotFound(ObjectCreatedResponse[String]("not_found","scan_target",targetName).asJson))
  }

  def listScanTargets = Action.async {
    val alpakkaClient = ddbClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

    ScanamoAlpakka.exec(alpakkaClient)(table.scan()).map({ result=>
      val errors = result.collect({
        case Left(readError)=>readError
      })

      if(errors.isEmpty){
        val success = result.collect({
          case Right(scanTarget)=>scanTarget
        })
        Ok(ObjectListResponse[List[ScanTarget]]("ok","scan_target",success,success.length).asJson)
      } else {
        InternalServerError(GenericErrorResponse("error", errors.map(_.toString).mkString(",")).asJson)
      }
    })
  }

  private def withLookup(targetName:String)(block: ScanTarget=>Result) = Scanamo.exec(ddbClientMgr.getNewDynamoClient(profileName))(table.get('bucketName -> targetName )).map({
    case Left(error)=>
      InternalServerError(GenericErrorResponse("error", error.toString).asJson)
    case Right(tgt)=>
      block(tgt)
  }).getOrElse(NotFound(ObjectCreatedResponse[String]("not_found","scan_target",targetName).asJson))

  def manualTrigger(targetName:String) = Action {
    withLookup(targetName) { tgt=>
      bucketScanner ! new BucketScanner.PerformDeletionScan(tgt,thenScanForNew=true)
      Ok(GenericErrorResponse("ok", "scan started").asJson)
    }
  }

  def manualTriggerAdditionScan(targetName:String) = Action {
    withLookup(targetName) { tgt=>
      bucketScanner ! new BucketScanner.PerformTargetScan(tgt)
      Ok(GenericErrorResponse("ok", "scan started").asJson)
    }
  }

  def manualTriggerDeletionScan(targetName:String) = Action {
    withLookup(targetName) { tgt=>
      bucketScanner ! new BucketScanner.PerformDeletionScan(tgt)
      Ok(GenericErrorResponse("ok","scan started").asJson)
    }
  }
}
