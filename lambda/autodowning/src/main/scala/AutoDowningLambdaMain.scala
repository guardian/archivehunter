import akka.actor.ActorSystem
import akka.stream.Materializer
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger, RequestHandler}
import com.typesafe.config.{Config, ConfigFactory}
import org.scanamo.{Scanamo, Table}
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import models._
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeInstancesRequest, DescribeTagsRequest, Filter, Instance, TagDescription}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class AutoDowningLambdaMain extends RequestHandler[java.util.LinkedHashMap[String,Object],Unit] with LifecycleMessageDecoder{
  import EnhancedLambdaLogger._

  private val actorSystemConfig:Config = ConfigFactory.empty()
  implicit val actorSystem = ActorSystem("akka-comms",config=actorSystemConfig, classLoader=getClass.getClassLoader)
  implicit val mat:Materializer = Materializer.matFromSystem

  def getInstanceTableName =
    sys.env.get("INSTANCES_TABLE") match {
      case Some(table)=>table
      case None=>
        throw new RuntimeException("You must set INSTANCES_TABLE to the dynamodb table that tracks ec2 instances")
    }

  val instanceTableName = getInstanceTableName

  val instanceTable = Table[InstanceIp](instanceTableName)
  val ec2Client = Ec2Client.builder().build()
  val ddbClient = DynamoDbClient.builder().build()
  val scanamo = Scanamo(ddbClient)

  val akkaComms = new AkkaComms(getLoadBalancerHost, 8558)

  val tagsComparison = Map("App"->"APP_TAG", "Stack"->"STACK_TAG", "Stage"->"STAGE_TAG")


  /**
    * provide the hostname of the loadbalancer to contact. Implemented as a seperate method in order to over-ride when testing
    * @return string of the loadbalancer config. Raises if the environment variable "LOADBALANCER" is not set.
    */
  protected def getLoadBalancerHost = sys.env("LOADBALANCER")

  /**
    * get EC2 metadata about the given instance ID. This requires Describe permissions on the EC2 resource in question.
    * @param instanceId EC2 instance ID to query (string)
    * @return Success with an instance if we could get the information. Success with None if there was no data available, or
    *         Failure with an error
    */
  def getEc2Info(instanceId:String):Try[Option[Instance]] = Try {
    val rq = DescribeInstancesRequest.builder().instanceIds(instanceId).build()
    val result = ec2Client.describeInstances(rq)

    result.reservations().asScala.headOption.flatMap(_.instances().asScala.headOption)
  }

  /**
    * extract the private IP address from the given instance data
    * @param instance instance of the `Instance` class from EC2. Get this by calling `getEc2Info`.
    * @return Success with IP address as a string if it was present, success with None if it was not, or Failure with
    *         an error
    */
  def getEc2Ip(instance:Instance) = Try {
    val interfaces = instance.networkInterfaces().asScala.headOption
    interfaces.map(_.privateIpAddress())
  }

  /**
    * extract the list of tags from the given instance data
    * @param instance instance of the `Instance` class from EC2. Get this by calling `getEc2Info`
    * @return a Sequence of `Tag` instances.
    */
  def getEc2Tags(instance:Instance):Seq[TagDescription] = {
    val rq = DescribeTagsRequest.builder().filters(
      Filter.builder().name("resource-id").values(instance.instanceId).build(),
      Filter.builder().name("resource-type").values("instance").build()
   ).build()
    val result = ec2Client.describeTags(rq)
    result.tags().asScala.toSeq
  }

  def addRecord(rec:InstanceIp) = Try { scanamo.exec(instanceTable.put(rec)) }

  def findRecord(instanceId:String) = scanamo.exec(instanceTable.get("instanceId"===instanceId))

  def deleteRecord(rec:InstanceIp) = Try { scanamo.exec(instanceTable.delete("instanceId"->rec.instanceId)) }

  def findAkkaNode(ipAddress:String, allNodes:Seq[AkkaMember]) =
    allNodes.find(_.node.getHost==ipAddress)

  /**
    * process the fact that an EC2 instance has terminated, by looking up the IP address that it was associated with
    * and telling Akka (via HTTP) to remove it from the cluster
    * @param details [[LifecycleDetails]] instance, provided from the Cloudwatch Event
    * @param attempt if communication to AWS services fails for any reason, the process is retried at 5s intervals, incrementing
    *                this parameter. Default value is 0; leave this off when calling.
    */
  def registerInstanceTerminated(details: LifecycleDetails, attempt:Int=0)(implicit logger: LambdaLogger):Unit =
    findRecord(details.EC2InstanceId.get) match {
      case Some(Right(record))=>
        logger.info(s"Downing node for $record")
        Await.result(akkaComms.getNodes().flatMap(akkaNodes=>{
          logger.info(s"Got $akkaNodes")
          akkaNodes.foreach(info=>logger.info(s"Got akka node: $info"))
          findAkkaNode(record.ipAddress, akkaNodes) match {
            case None=>
              logger.error(s"Could not find node ${details.EC2InstanceId.get} in the Akka cluster")
              Future(false)
            case Some(akkaNode)=>
              akkaComms.downAkkaNode(akkaNode)
          }
        }), 60.seconds)
      case None=>
        throw new RuntimeException(s"No record returned for ${details.EC2InstanceId}")
      case Some(Left(err))=>
        logger.warn(s"Could not contact dynamodb: $err")
        if(attempt>100) throw new RuntimeException(s"Could not contact dynamodb: $err")
        Thread.sleep(5000)
        registerInstanceTerminated(details, attempt+1)
    }

  /**
    * process the fact that an instance has started, by recording its instance ID and IP address in DynamoDB.
    * This is necessary since akka requires the IP address when removing the node, but we can't get that data once
    * the EC2 instance starts to terminate.  We do have the instance ID though.
    * @param details [[LifecycleDetails]] object from the Cloudwatch Event
    * @param instance `Instance` instance from EC2 SDK
    * @param attempt retry attempt; see `registerInstanceTerminated` for details. Leave this off when calling.
    */
  def registerInstanceStarted(details: LifecycleDetails, instance:Instance, attempt:Int=0)(implicit logger: LambdaLogger):Unit =
    getEc2Ip(instance) match {
      case Success(Some(ipAddr)) =>
        val record = InstanceIp(instance.instanceId(), ipAddr)
        logger.info(s"Got IP address $ipAddr for ${instance.instanceId()}")

        addRecord(record) match {
          case Success(_)=>
          case Failure(err)=>
            logger.warn(s"Could not contact dynamodb: $err")
            if(attempt>100) throw new RuntimeException(s"Could not contact dynamodb: $err")
            Thread.sleep(1000)
            registerInstanceStarted(details, instance, attempt+1)
        }
      case Success(None)=>
        throw new RuntimeException(s"Error - no IP address could be found for ${details.EC2InstanceId.get}")
      case Failure(err) =>
        throw new RuntimeException(s"Could not get IP address for: ${err.toString}")
    }

  /**
    * decide what to do with the given event. This involves contacting EC2 to load in metadata about it.
    * @param details [[LifecycleDetails]] instance from the Cloudwatch event
    * @param state current state of the EC2 instance in question, this is extracted already from `details`
    * @param attempt attempt number. See `registerInstaceTerminated` for details. Leave this off when calling.
    */
  def processInstance(details: LifecycleDetails, state:String, attempt:Int=0, maxRetries:Int=100)(implicit logger:LambdaLogger):Unit = getEc2Info(details.EC2InstanceId.get) match {
    case Success(Some(info))=>
      println(s"Got $info")
      if(shouldHandle(info)) {
        if (state == "terminated") {
          println("registering shutdown")
          registerInstanceTerminated(details)
        } else if(state == "running"){
          println("registering startup")
          registerInstanceStarted(details, info)
        } else {
          println(s"don't need to register $state state")
        }
      } else {
        println("not handling")
        logger.info(s"We are not interested in this instance, tags are ${getEc2Tags(info)} but we want $tagsComparison")
      }
    case Success(None)=>
      throw new RuntimeException(s"No details returned from EC2 for instance ${details.EC2InstanceId.get}")
    case Failure(exception)=>
      logger.warn(s"Could not contact EC2 on attempt $attempt: ", exception)
      if(attempt>maxRetries) throw exception
      Thread.sleep(1000)
      processInstance(details, state, attempt+1, maxRetries)
  }

  /**
    * return the tag value to check from a config key. included as an extra method to make testing easier.
    * @param configKey key to read
    * @return an Option with the value or None if it does not exist/
    */
  protected def getTagConfigValue(configKey:String) = sys.env.get(configKey)

  /**
    * returns a boolean if the tags for this instance match the ones that we are configured to manage
    * @param instance Ec2 instance model describing the instance in question
    */
//  def shouldHandle(instance:Instance):Boolean = {
//    val tags = getEc2Tags(instance)
//    logger.debug(s"got tags $tags")
//    val usefulTags = tagsComparison.keys
//      .foldLeft[Seq[TagDescription]](Seq())((acc, entry)=>acc ++ tags.filter(_.getKey==entry))
//        .map(t=>(t.getKey, t.getValue))
//        .toMap
//
//    logger.debug(s"usefulTags are $usefulTags")
//
//    val unmatchedTags = tagsComparison.filter(comp=>{
//      logger.debug(s"comparator: ${comp._1} to ${comp._2}")
//      usefulTags.get(comp._1) match {
//        case Some(matchedTagValue)=>
//          logger.debug (s"values: $matchedTagValue vs ${getTagConfigValue(comp._2)}")
//          getTagConfigValue (comp._2) match {
//            case None => false
//            case Some (configValue) => matchedTagValue != configValue
//          }
//        case None=>
//          logger.warn(s"Instance was missing required tag ${comp._1}")
//          return false
//      }
//    })
//    logger.debug(s"got $unmatchedTags unmatched tags")
//    unmatchedTags.isEmpty
//  }
    //ok, so that SHOULD work but i don't seem to get tags for Pending or Running instnaces (??)
    //so, just accept everything and have done with it
  def shouldHandle(instance: Instance):Boolean = true

  def handleRequest(input:java.util.LinkedHashMap[String,Object], context:Context) = {
    val msg = LifecycleMessage.fromLinkedHashMap(input)
    implicit val logger:LambdaLogger = context.getLogger

    msg.detail match {
      case Some(details)=>
        details.state match {
          case Some(state)=>
            logger.log(s"INFO $state instance; message from ${msg.source} in ${msg.region}")
            processInstance(details, state)
          case None=>
            println(s"Message from ${msg.source} in ${msg.region}")
            println(s"Message details: ${msg.detailType}, ${msg.detail}")
            logger.log("ERROR Received no instance state, can't proceed")
            throw new RuntimeException("Received no instance state, can't proceed")
        }
      case None=>
        println(s"Message from ${msg.source} in ${msg.region}")
        println(s"Message details: ${msg.detailType}, ${msg.detail}")
        throw new RuntimeException("Received no instance details, can't proceed")
    }
  }
}
