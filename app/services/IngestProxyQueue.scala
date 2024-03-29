package services

import akka.actor.{Actor, ActorRef, ActorSystem, Status}
import akka.stream.{ActorMaterializer, Materializer}
import io.circe.syntax._
import io.circe.generic.auto._
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.{ProxyGenerators, RequestType}
import com.theguardian.multimedia.archivehunter.common.{cmn_models, _}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager, SQSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IngestMessage, ScanTargetDAO}
import helpers.ProxyLocator

import javax.inject.{Inject, Named, Singleton}
import models.AwsSqsMsg
import play.api.{Configuration, Logger}
import software.amazon.awssdk.regions.Region

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

  case class StartAnalyse(entry:ArchiveEntry) extends IPQMsg

}

@Singleton
class IngestProxyQueue @Inject()(config: Configuration,
                                 system: ActorSystem,
                                 sqsClientManager: SQSClientManager,
                                 proxyGenerators: ProxyGenerators,
                                 s3ClientMgr: S3ClientManager,
                                 dynamoClientMgr: DynamoClientManager,
                                 esClientMgr:ESClientManager,
                                )(implicit scanTargetDAO: ScanTargetDAO, proxyLocationDAO: ProxyLocationDAO, override val mat:Materializer)
  extends GenericSqsActor[IngestMessage] with ZonedDateTimeEncoder with StorageClassEncoder {

  import IngestProxyQueue._
  import GenericSqsActor._

  private val logger = Logger(getClass)

  lazy override protected val sqsClient = sqsClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))

  override protected implicit val implSystem = system

  private implicit val esClient = esClientMgr.getClient()
  lazy protected implicit val indexer = new Indexer(config.get[String]("externalData.indexName"))

  //override this in testing
  protected val ownRef: ActorRef = self

  override protected implicit val ec: ExecutionContext = system.dispatcher

  override protected val notificationsQueue = config.get[String]("ingest.notificationsQueue")

  private implicit val ddbClient = dynamoClientMgr.getNewAsyncDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  lazy val defaultRegion = config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1")

  override def convertMessageBody(body: String): Either[io.circe.Error, IngestMessage] =
    io.circe.parser.parse(body).flatMap(_.as[IngestMessage])

  override def receive: Receive = {
    case StartAnalyse(entry) =>
      val originalSender = sender()
      proxyGenerators.requestMetadataAnalyse(entry,defaultRegion).onComplete({
        case Success(Left(problem))=>
          logger.error(s"Could not request analyse for $entry: $problem")
          originalSender ! Status.Failure(new RuntimeException(problem))
        case Success(Right(status))=>
          logger.info(s"Started metadata analyse: $status")
          originalSender ! Status.Success
        case Failure(err)=>
          logger.error("Metadata request thread failed: ", err)
          originalSender ! Status.Failure(err)
      })

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
      implicit val s3Client = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"), entry.region.map(Region.of))
      ProxyLocator.findProxyLocation(entry).map(results => {
        val foundProxies = results.collect({ case Right(loc) => loc }).filter(loc => loc.proxyType == ProxyType.THUMBNAIL)
        if (foundProxies.isEmpty) {
          logger.info(s"${entry.bucket}:${entry.path} has no locatable thumbnails in expected locations. Generating a new one...")
          ownRef ! CreateNewThumbnail(entry)
        } else {
          logger.info(s"${entry.bucket}:${entry.path}: Found existing potential thumbnails: $foundProxies")
          //add given items to the proxies table for the item and then update the index record to say it's proxied
          Future
            .sequence(foundProxies.map(proxyLocationDAO.saveProxy))
            .map(results => {
              ProxyLocator.setProxiedWithRetry(entry.id)
              results
            })
        }
      }).onComplete({
        case Success(_) => ()
        case Failure(err) =>
          logger.error(s"${entry.bucket}:${entry.path}:  Could not run proxy location find: ", err)
          originalSender ! Status.Failure
      })

    case CreateNewThumbnail(entry) =>
      val originalSender = sender()
      proxyGenerators.requestProxyJob(RequestType.THUMBNAIL,entry,None).onComplete({
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
      implicit val s3Client = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"), entry.region.map(Region.of))
      ProxyLocator.findProxyLocation(entry).flatMap(results => {
        val foundProxies = results.collect({ case Right(loc) => loc }).filter(loc => loc.proxyType != ProxyType.THUMBNAIL)
        if (foundProxies.isEmpty) {
          logger.info(s"${entry.bucket}:${entry.path} has no locatable proxies in expected locations. Generating a new one...")
          proxyGenerators.defaultProxyType(entry) match {
            case Some(proxyType) =>
              proxyGenerators.requestProxyJob(RequestType.PROXY, entry, Some(proxyType)).map(result=>Seq(result))
            case None=>
              logger.error(s"No default proxy type available for ${entry.bucket}:${entry.path} (${entry.mimeType.toString})")
              throw new RuntimeException("No default proxy type available")
          }
        } else {
          logger.info(s"${entry.bucket}:${entry.path} has unregistered proxies: $foundProxies")
          Future
            .sequence(foundProxies.map(proxyLocationDAO.saveProxy))
            .map(results => {
              ProxyLocator.setProxiedWithRetry(entry.id)
              results
            })
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

    case HandleDomainMessage(finalMsg:IngestMessage, queueUrl, receiptHandle)=>
      logger.info(s"Received notification of new item: ${finalMsg.archiveEntry}")
      indexer.getById(finalMsg.archiveEntry.id).onComplete({
        case Success(entry)=>
          logger.info(s"DEBUGGING - indexed entry for ${finalMsg.archiveEntry.id} was $entry at point of receive")
        case Failure(err)=>
          logger.error(s"Could not check indexed entry for ${finalMsg.archiveEntry.id}: ${err.getMessage}", err)
      })

      ownRef ! CheckRegisteredThumb(finalMsg.archiveEntry)
      ownRef ! CheckRegisteredProxy(finalMsg.archiveEntry)
      ownRef ! StartAnalyse(finalMsg.archiveEntry)
      sqsClient.deleteMessage(new DeleteMessageRequest().withQueueUrl(queueUrl).withReceiptHandle(receiptHandle))
      ownRef ! ReadyForNextMessage  //tell the superclass we are ready for the next message

    case other:GenericSqsActor.SQSMsg => handleGeneric(other)
  }
}
