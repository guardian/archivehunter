import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import org.apache.logging.log4j.LogManager
import com.amazonaws.services.ec2
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{DescribeInstanceAttributeRequest, DescribeInstancesRequest, InstanceAttributeName}
import io.circe.syntax._
import models.{LifecycleMessage, LifecycleMessageDecoder}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class AutoDowningLambdaMain extends RequestHandler[String,Unit] with LifecycleMessageDecoder{
  private final val logger = LogManager.getLogger(getClass)

  val ec2Client = AmazonEC2ClientBuilder.defaultClient()

  def getEc2Ip(instanceId:String) = Try {
    val rq = new DescribeInstancesRequest().withInstanceIds(instanceId)
    val result = ec2Client.describeInstances(rq)

    result.getReservations.asScala.headOption.flatMap(_.getInstances.asScala.headOption).map(_.getNetworkInterfaces.asScala)

    val interfaces = for {
      res <- result.getReservations.asScala.headOption
      instances <- res.getInstances.asScala.headOption
      nets <- instances.getNetworkInterfaces.asScala.headOption
    } yield nets

    interfaces.map(_.getPrivateIpAddress)
  }

  def handleRequest(input:String, context:Context) = {
    println("Got input: ")
    println(input)

    io.circe.parser.parse(input).flatMap(_.as[LifecycleMessage]) match {
      case Left(err)=>
        println(s"Could not parse input: $err")
      case Right(msg)=>
        println(s"Message from ${msg.source} in ${msg.region}")
        println(s"Message details: ${msg.detailType}, ${msg.detail}")
        msg.detail.flatMap(_.EC2InstanceId) match {
          case Some(instanceId)=>
            getEc2Ip(instanceId) match {
              case Success(ipAddr)=>
                println(s"Got IP address $ipAddr for $instanceId")
              case Failure(err)=>
                println(s"Could not get IP address for $instanceId: ${err.toString}")
            }
          case None=>
            println(s"ERROR: could not get an instance ID from message")
        }

    }
  }
}
