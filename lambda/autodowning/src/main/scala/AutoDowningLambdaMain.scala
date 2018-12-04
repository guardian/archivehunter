import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import org.apache.logging.log4j.LogManager
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{DescribeInstanceAttributeRequest, DescribeInstancesRequest, InstanceAttributeName}
import com.google.inject.Guice
import com.gu.scanamo.{Scanamo, Table}
import com.gu.scanamo.syntax._
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import models.{InstanceIp, LifecycleDetails, LifecycleMessage, LifecycleMessageDecoder}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class AutoDowningLambdaMain extends RequestHandler[java.util.LinkedHashMap[String,Object],Unit] with LifecycleMessageDecoder{
  private final val logger = LogManager.getLogger(getClass)

  private val injector = Guice.createInjector(new Module)

  val config = injector.getInstance(classOf[ArchiveHunterConfiguration])
  val instanceTableName = config.get("instances.tableName")
  val instanceTable = Table[InstanceIp](instanceTableName)
  val ec2Client = AmazonEC2ClientBuilder.defaultClient()
  val ddbClientMgr = injector.getInstance(classOf[DynamoClientManager])
  val ddbClient = ddbClientMgr.getClient()

  val akkaComms = new AkkaComms(sys.env("LOADBALANCER"), 8558)

  def getEc2Ip(instanceId:String) = Try {
    val rq = new DescribeInstancesRequest().withInstanceIds(instanceId)
    val result = ec2Client.describeInstances(rq)

    val instances = result.getReservations.asScala.headOption.flatMap(_.getInstances.asScala.headOption)
    println(instances)

    val interfaces = for {
      res <- result.getReservations.asScala.headOption
      instances <- res.getInstances.asScala.headOption
      nets <- instances.getNetworkInterfaces.asScala.headOption
    } yield nets

    interfaces.map(_.getPrivateIpAddress)
  }

  def addRecord(rec:InstanceIp) = Scanamo.exec(ddbClient)(instanceTable.put(rec))

  def findRecord(instanceId:String) = Scanamo.exec(ddbClient)(instanceTable.get('instanceId->instanceId))

  def deleteRecord(rec:InstanceIp) = Scanamo.exec(ddbClient)(instanceTable.delete('instanceId->rec.instanceId))

  def registerInstanceTerminated(details: LifecycleDetails, attempt:Int=0):Future[Unit] =
    findRecord(details.EC2InstanceId.get) match {
      case Some(Right(record))=>
        logger.info(s"Downing node for $record")
        akkaComms.getNodes().map(akkaNodes=>{
          akkaNodes.foreach(info=>logger.info(s"Got akka node: $info"))
        })
//        downAkkaNode(record.ipAddress).onCompleted({
//          case Success(result)=>
//            deleteRecord(record)
//            logger.info("Downing completed")
//          case Failure(err)=>
//            logger.error(s"Could not complete downing message: ${err.toString}")
//            throw err
//        })
      case None=>
        throw new RuntimeException(s"No record returned for ${details.EC2InstanceId}")
      case Some(Left(err))=>
        logger.warn(s"Could not contact dynamodb: $err")
        if(attempt>100) throw new RuntimeException(s"Could not contact dynamodb: $err")
        Thread.sleep(5000)
        registerInstanceTerminated(details, attempt+1)
    }

  def registerInstanceStarted(details: LifecycleDetails, attempt:Int=0):Unit =
    getEc2Ip(details.EC2InstanceId.get) match {
      case Success(Some(ipAddr)) =>
        val record = InstanceIp(details.EC2InstanceId.get, ipAddr)
        logger.info(s"Got IP address $ipAddr for ${details.EC2InstanceId.get}")
        addRecord(record) match {
          case Some(Right(newRecord))=>
            logger.info("Record saved")
          case None=>
            logger.info("Record saved")
          case Some(Left(err))=>
            logger.warn(s"Could not contact dynamodb: $err")
            if(attempt>100) throw new RuntimeException(s"Could not contact dynamodb: $err")
            Thread.sleep(5000)
            registerInstanceStarted(details, attempt+1)
        }
      case Success(None)=>
        throw new RuntimeException(s"Error - no IP address could be found for ${details.EC2InstanceId.get}")
      case Failure(err) =>
        throw new RuntimeException(s"Could not get IP address for: ${err.toString}")
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
        details.state match {
          case Some(state)=>
            if(state=="terminating" || state=="terminated"){
              Await.ready(registerInstanceTerminated(details), 60 seconds)
            } else {
              registerInstanceStarted(details)
            }
          case None=>
            logger.error("Received no instance state, can't proceed")
            throw new RuntimeException("Received no instance state, can't proceed")
        }
      case None=>throw new RuntimeException("Received no instance details, can't proceed")
    }
  }
}
