import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import io.circe.generic.auto._
import io.circe.syntax._
import models.AkkaMembersResponse

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Communicate with Akka cluster management endpoints
  */
class AkkaComms (contactAddress:String, contactPort:Int) {
  implicit val actorSystem = ActorSystem.create("akka-comms")
  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  def consumeResponseEntity(entity:HttpEntity) = {
    entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
  }

  def getNodes() = {
    Http().singleRequest(HttpRequest(uri=s"http://$contactAddress:$contactPort/cluster/members"))
      .flatMap(response=>{
        consumeResponseEntity(response.entity).map(responseBody=>
          response.status match {
            case StatusCodes.OK =>
              io.circe.parser.parse(responseBody).flatMap(_.as[AkkaMembersResponse]) match {
                case Left(err)=>
                  throw new RuntimeException(s"Could not decode response from server: $err")
                case Right(result)=>
                  result.members
              }
            case _=>
              throw new RuntimeException(s"Endpoint gave an unexpected response: Status code ${response.status}, body $responseBody")
          }
        )
      })
  }
}
