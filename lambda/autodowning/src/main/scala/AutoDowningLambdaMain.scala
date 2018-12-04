import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import org.apache.logging.log4j.LogManager
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{DescribeInstanceAttributeRequest, DescribeInstancesRequest, InstanceAttributeName}
import models.{LifecycleDetails, LifecycleMessage, LifecycleMessageDecoder}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class AutoDowningLambdaMain extends RequestHandler[java.util.LinkedHashMap[String,Object],Unit] with LifecycleMessageDecoder{
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

  def handleRequest(input:java.util.LinkedHashMap[String,Object], context:Context) = {
    println("Got input: ")
    println(input)

    val msg = LifecycleMessage.fromLinkedHashMap(input)
    println(msg)

    println(s"Message from ${msg.source} in ${msg.region}")
    println(s"Message details: ${msg.detailType}, ${msg.detail}")

    msg.detail match {
      case Some(details)=>
        getEc2Ip(details.EC2InstanceId.get) match {
          case Success(ipAddr) =>
            println(s"Got IP address $ipAddr for")
          case Failure(err) =>
            println(s"Could not get IP address for: ${err.toString}")
        }

    }


  }
}
