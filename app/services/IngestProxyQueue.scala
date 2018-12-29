package services

import akka.actor.{Actor, ActorRef, ActorSystem, Status}
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocationDAO, ProxyType}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTargetDAO
import com.theguardian.multimedia.archivehunter.common.cmn_services.ProxyGenerators
import helpers.ProxyLocator
import javax.inject.{Inject, Named}
import models.AwsSqsMsg
import play.api.{Configuration, Logger}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object IngestProxyQueue {
  trait IPQMsg

  case object CheckForNotifications extends IPQMsg

  /* private internal messages */
  case class HandleNextSqsMessage(rq:ReceiveMessageRequest) extends IPQMsg

  case class CheckRegisteredProxy(entry:ArchiveEntry) extends IPQMsg
  case class CheckNonRegisteredProxy(entry:ArchiveEntry) extends IPQMsg

  case class CheckRegisteredThumb(entry:ArchiveEntry) extends IPQMsg
  case class CheckNonRegisteredThumb(entry: ArchiveEntry) extends IPQMsg
  case class CreateNewThumbnail(entry:ArchiveEntry) extends IPQMsg

}


class IngestProxyQueue @Inject() (config:Configuration,
                                  system:ActorSystem,
                                  sqsClientManager: SQSClientManager,
                                  proxyGenerators:ProxyGenerators,
                                  proxyLocationDAO: ProxyLocationDAO,
                                  s3ClientMgr:S3ClientManager,
                                  dynamoClientMgr:DynamoClientManager,
                                  @Named("etsProxyActor") etsProxyActor:ActorRef
                                 )(implicit scanTargetDAO: ScanTargetDAO)
  extends Actor {
  import IngestProxyQueue._
  private val logger = Logger(getClass)

  private val sqsClient = sqsClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))

  //override this in testing
  protected val ipqActor:ActorRef = self

  private implicit val ec:ExecutionContext = system.dispatcher

  private implicit val s3Client = s3ClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))
  private implicit val ddbClient = dynamoClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))

  override def receive: Receive = {
    case CheckRegisteredThumb(entry)=>
      val originalSender = sender()
      proxyLocationDAO.getProxy(entry.id, ProxyType.THUMBNAIL).map({
        case Some(proxyLocation)=>
          logger.info(s"${entry.bucket}:${entry.path} already has a registered thumbnail at $proxyLocation")
          originalSender ! Status.Success
        case None=>
          logger.info(s"${entry.bucket}:${entry.path} has no registered thumbnail")
          ipqActor ! CheckNonRegisteredThumb(entry)
      }).onComplete({
        case Success(_)=>()
        case Failure(err)=>
          logger.error("Could not look up proxy data: ", err)
          originalSender ! Status.Failure
      })

    case CheckNonRegisteredThumb(entry)=>
      val originalSender = sender()
      ProxyLocator.findProxyLocation(entry).map(results=>{
        val foundProxies = results.collect({case Right(loc)=>loc}).filter(loc=>loc.proxyType==ProxyType.THUMBNAIL)
        if(foundProxies.isEmpty){
          logger.info(s"${entry.bucket}:${entry.path} has no locatable thumbnails in expected locations. Generating a new one...")
          ipqActor ! CreateNewThumbnail(entry)
        } else {
          logger.info(s"${entry.bucket}:${entry.path}: Found existing potential thumbnails: $foundProxies")
          //FIXME: should link back to item
        }
      }).onComplete({
        case Success(_)=>()
        case Failure(err)=>
          logger.error(s"${entry.bucket}:${entry.path}:  Could not run proxy location find: ", err)
          originalSender ! Status.Failure
      })

    case CreateNewThumbnail(entry)=>
      val originalSender = sender()
      proxyGenerators.createThumbnailProxy(entry).onComplete({
        case Success(Success(result))=> //thread completed and we got a result
          logger.info(s"${entry.bucket}:${entry.path}: started thumbnailing with ECS id $result")
          originalSender ! Status.Success
        case Success(Failure(err))=> //thread completed OK but we did not start a job
          logger.error(s"${entry.bucket}:${entry.path}: Could not start thumbnailing:", err)
          originalSender ! Status.Failure
        case Failure(err)=>
          logger.error(s"${entry.bucket}:${entry.path}: thumbnailing thread failed", err)
          originalSender ! Status.Failure
      })

    case CheckNonRegisteredProxy(entry)=>
      val originalSender = sender()
      ProxyLocator.findProxyLocation(entry).map(results=>{
        val foundProxies = results.collect({case Right(loc)=>loc}).filter(loc=>loc.proxyType!=ProxyType.THUMBNAIL)
        if(foundProxies.isEmpty){
          logger.info(s"${entry.bucket}:${entry.path} has no locatable proxies in expected locations. Generating a new one...")
          etsProxyActor ! ETSProxyActor.CreateDefaultMediaProxy(entry)
        } else {
          logger.info(s"${entry.bucket}:${entry.path} has unregistered proxies: $foundProxies")
          //FIXME: need to register proxies here
        }
      }).onComplete({
        case Success(_)=>()
        case Failure(err)=>
          logger.error(s"${entry.bucket}:${entry.path}: findProxyLocation failed: ", err)
          originalSender ! Status.Failure
      })

    case CheckRegisteredProxy(entry)=>
      val possibleProxyTypes = Seq(ProxyType.AUDIO, ProxyType.VIDEO)
      val originalSender = sender()

      Future.sequence(possibleProxyTypes.map(pt=>proxyLocationDAO.getProxy(entry.id,pt))).map(results=>{
        val validProxies = results.collect({case Some(proxyLocation)=>proxyLocation})
        println(validProxies.toString)
        if(validProxies.isEmpty){
          logger.info(s"${entry.bucket}:${entry.path} has no known proxies, checking for loose...")
          ipqActor ! CheckNonRegisteredProxy(entry)
        } else {
          logger.info(s"${entry.bucket}:${entry.path} has these known proxies: $validProxies")
          originalSender ! Status.Success
        }
      }).onComplete({
        case Success(_)=>
          ()
        case Failure(err)=>
          logger.error("Could not check for existing proxies", err)
          originalSender ! Status.Failure
      })

    //dispatched to pull all messages off the queue. This "recurses" by dispatching itself if there are messages left on the queue.
    case HandleNextSqsMessage(rq:ReceiveMessageRequest)=>
      val result = sqsClient.receiveMessage(rq)
      val msgList = result.getMessages.asScala
      if(msgList.nonEmpty){
        msgList.foreach(msg=> {
          logger.debug(s"Received message ${msg.getMessageId}:")
          logger.debug(s"\tAttributes: ${msg.getAttributes.asScala}")
          logger.debug(s"\tReceipt Handle: ${msg.getReceiptHandle}")
          logger.debug(s"\tBody: ${msg.getBody}")

          AwsSqsMsg.fromJsonString(msg.getBody).flatMap(_.getIngestMessge) match {
            case Left(err)=>
              logger.error(s"Could not decode message from queue: $err")
              sender ! Status.Failure
            case Right(finalMsg)=>
              logger.info(s"Received notification of new item: ${finalMsg.archiveEntry}")
              ipqActor ! CheckRegisteredThumb(finalMsg.archiveEntry)
              ipqActor ! CheckRegisteredProxy(finalMsg.archiveEntry)
          }
        })
        self ! HandleNextSqsMessage(rq)
      } else {
        sender ! Status.Success
      }

    case CheckForNotifications=>
      logger.debug("CheckForNotifications")
      val notificationsQueue=config.get[String]("ingest.notificationsQueue")
      val rq = new ReceiveMessageRequest().withQueueUrl(notificationsQueue)
      if(notificationsQueue=="queueUrl"){
        logger.warn("ingest notifications queue not set up in applications.conf")
      } else {
        ipqActor ! HandleNextSqsMessage(rq)
      }
  }
}
