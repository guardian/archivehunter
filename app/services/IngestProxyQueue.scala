package services

import akka.actor.{Actor, ActorRef, ActorSystem, Status}
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import com.theguardian.multimedia.archivehunter.common.{cmn_models, _}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IngestMessage, ScanTargetDAO}
import com.theguardian.multimedia.archivehunter.common.cmn_services.ProxyGenerators
import helpers.ProxyLocator
import javax.inject.{Inject, Named}
import models.AwsSqsMsg
import play.api.{Configuration, Logger}
import io.circe.syntax._
import io.circe.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object IngestProxyQueue extends GenericSqsActorMessages {
  trait IPQMsg extends SQSMsg

  case class CheckRegisteredProxy(entry:ArchiveEntry) extends IPQMsg
  case class CheckNonRegisteredProxy(entry:ArchiveEntry) extends IPQMsg

  case class CheckRegisteredThumb(entry:ArchiveEntry) extends IPQMsg
  case class CheckNonRegisteredThumb(entry: ArchiveEntry) extends IPQMsg
  case class CreateNewThumbnail(entry:ArchiveEntry) extends IPQMsg

}


class IngestProxyQueue @Inject()(config: Configuration,
                                 system: ActorSystem,
                                 sqsClientManager: SQSClientManager,
                                 proxyGenerators: ProxyGenerators,
                                 s3ClientMgr: S3ClientManager,
                                 dynamoClientMgr: DynamoClientManager,
                                 @Named("etsProxyActor") etsProxyActor: ActorRef
                                )(implicit scanTargetDAO: ScanTargetDAO, proxyLocationDAO: ProxyLocationDAO)
  extends GenericSqsActor with ZonedDateTimeEncoder with StorageClassEncoder {

  import IngestProxyQueue._
  import GenericSqsActor._

  private val logger = Logger(getClass)

  override protected val sqsClient = sqsClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))

  override protected implicit val implSystem = system
  override protected implicit val mat: Materializer = ActorMaterializer.create(system)

  //override this in testing
  protected val ownRef: ActorRef = self

  override protected implicit val ec: ExecutionContext = system.dispatcher

  override protected val notificationsQueue = config.get[String]("ingest.notificationsQueue")

  private implicit val s3Client = s3ClientMgr.getClient(config.getOptional[String]("externalData.awsProfile"))
  private implicit val ddbClient = dynamoClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  override def receive: Receive = {
    case CheckRegisteredThumb(entry) =>
      val originalSender = sender()
      proxyLocationDAO.getProxy(entry.id, ProxyType.THUMBNAIL).map({
        case Some(proxyLocation) =>
          logger.info(s"${entry.bucket}:${entry.path} already has a registered thumbnail at $proxyLocation")
          originalSender ! Status.Success
        case None =>
          logger.info(s"${entry.bucket}:${entry.path} has no registered thumbnail")
          ownRef ! CheckNonRegisteredThumb(entry)
      }).onComplete({
        case Success(_) => ()
        case Failure(err) =>
          logger.error("Could not look up proxy data: ", err)
          originalSender ! Status.Failure
      })

    case CheckNonRegisteredThumb(entry) =>
      val originalSender = sender()
      ProxyLocator.findProxyLocation(entry).map(results => {
        val foundProxies = results.collect({ case Right(loc) => loc }).filter(loc => loc.proxyType == ProxyType.THUMBNAIL)
        if (foundProxies.isEmpty) {
          logger.info(s"${entry.bucket}:${entry.path} has no locatable thumbnails in expected locations. Generating a new one...")
          ownRef ! CreateNewThumbnail(entry)
        } else {
          logger.info(s"${entry.bucket}:${entry.path}: Found existing potential thumbnails: $foundProxies")
          //FIXME: should link back to item
        }
      }).onComplete({
        case Success(_) => ()
        case Failure(err) =>
          logger.error(s"${entry.bucket}:${entry.path}:  Could not run proxy location find: ", err)
          originalSender ! Status.Failure
      })

    case CreateNewThumbnail(entry) =>
      val originalSender = sender()
      proxyGenerators.createThumbnailProxy(entry).onComplete({
        case Success(Success(result)) => //thread completed and we got a result
          logger.info(s"${entry.bucket}:${entry.path}: started thumbnailing with ECS id $result")
          originalSender ! Status.Success
        case Success(Failure(err)) => //thread completed OK but we did not start a job
          logger.error(s"${entry.bucket}:${entry.path}: Could not start thumbnailing:", err)
          originalSender ! Status.Failure
        case Failure(err) =>
          logger.error(s"${entry.bucket}:${entry.path}: thumbnailing thread failed", err)
          originalSender ! Status.Failure
      })

    case CheckNonRegisteredProxy(entry) =>
      val originalSender = sender()
      ProxyLocator.findProxyLocation(entry).map(results => {
        val foundProxies = results.collect({ case Right(loc) => loc }).filter(loc => loc.proxyType != ProxyType.THUMBNAIL)
        if (foundProxies.isEmpty) {
          logger.info(s"${entry.bucket}:${entry.path} has no locatable proxies in expected locations. Generating a new one...")
          etsProxyActor ! ETSProxyActor.CreateDefaultMediaProxy(entry)
        } else {
          logger.info(s"${entry.bucket}:${entry.path} has unregistered proxies: $foundProxies")
          //FIXME: need to register proxies here
        }
      }).onComplete({
        case Success(_) => ()
        case Failure(err) =>
          logger.error(s"${entry.bucket}:${entry.path}: findProxyLocation failed: ", err)
          originalSender ! Status.Failure
      })

    case CheckRegisteredProxy(entry) =>
      val possibleProxyTypes = Seq(ProxyType.AUDIO, ProxyType.VIDEO)
      val originalSender = sender()

      Future.sequence(possibleProxyTypes.map(pt => proxyLocationDAO.getProxy(entry.id, pt))).map(results => {
        val validProxies = results.collect({ case Some(proxyLocation) => proxyLocation })
        if (validProxies.isEmpty) {
          logger.info(s"${entry.bucket}:${entry.path} has no known proxies, checking for loose...")
          ownRef ! CheckNonRegisteredProxy(entry)
        } else {
          logger.info(s"${entry.bucket}:${entry.path} has these known proxies: $validProxies")
          originalSender ! Status.Success
        }
      }).onComplete({
        case Success(_) =>
          ()
        case Failure(err) =>
          logger.error("Could not check for existing proxies", err)
          originalSender ! Status.Failure
      })

    case HandleDomainMessage(finalMsg:IngestMessage, rq, receiptHandle)=>
      println(finalMsg)
      logger.info(s"Received notification of new item: ${finalMsg.archiveEntry}")
      ownRef ! CheckRegisteredThumb(finalMsg.archiveEntry)
      ownRef ! CheckRegisteredProxy(finalMsg.archiveEntry)
      sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(rq.getQueueUrl).withReceiptHandle(receiptHandle))

    case other:GenericSqsActor.SQSMsg => handleGeneric(other)
  }
}
