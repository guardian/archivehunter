import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model.{Multipart, _}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import io.circe.generic.auto._
import io.circe.syntax._
import models.{AkkaMember, AkkaMembersResponse, UriDecoder}
import org.apache.logging.log4j.LogManager

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Communicate with Akka cluster management endpoints
  */
class AkkaComms (contactAddress:String, contactPort:Int) extends UriDecoder {
  private val logger = LogManager.getLogger(getClass)

  implicit val actorSystem = ActorSystem.create("akka-comms")
  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  def consumeResponseEntity(entity:HttpEntity) = {
    entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
  }

  def getNodes() = {
    logger.debug(s"address is http://$contactAddress:$contactPort/cluster/members")
    Http().singleRequest(HttpRequest(uri=s"http://$contactAddress:$contactPort/cluster/members"))
      .flatMap(response=>{
        logger.debug(s"Got response ${response.status} from server")
        consumeResponseEntity(response.entity).map(responseBody=> {
          logger.debug(s"Got response body $responseBody")
          response.status match {
            case StatusCodes.OK =>
              io.circe.parser.parse(responseBody).flatMap(_.as[AkkaMembersResponse]) match {
                case Left(err) =>
                  throw new RuntimeException(s"Could not decode response from server: $err")
                case Right(result) =>
                  logger.debug(s"Returning $result")
                  result.members
              }
            case _ =>
              throw new RuntimeException(s"Endpoint gave an unexpected response: Status code ${response.status}, body $responseBody")
          }
        })
      })
  }

  def downAkkaNode(node:AkkaMember) = {
    val addr = s"http://$contactAddress:$contactPort/cluster/members/${node.node.toString}"
    logger.debug(s"address is $addr")
    Http().singleRequest(HttpRequest(HttpMethods.PUT,uri = addr,
      entity=akka.http.scaladsl.model.FormData(Map("operation"->"down")).toEntity
    ))
      .flatMap(response=>{
        logger.debug(s"Got response ${response.status}")
        consumeResponseEntity(response.entity).map(responseBody=>{
          logger.debug(s"Got response body $responseBody")
          response.status match {
            case StatusCodes.OK=>
              logger.info("Successfully executed down operation")
              true
            case _=>
              throw new RuntimeException(s"Endpoint gave an unexpected response: Status code ${response.status}, body $responseBody")
          }
        })
      })
  }
}
