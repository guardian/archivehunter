package services

import akka.actor.{Actor, ActorSystem, Cancellable}
import akka.stream.scaladsl.Keep
import akka.stream.{ActorMaterializer, KillSwitches}
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput
import com.google.inject.Injector
import com.theguardian.multimedia.archivehunter.common.ProxyLocation
import helpers._
import javax.inject.Inject
import models.{ScanTarget, ScanTargetDAO}
import play.api.{Configuration, Logger}

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object LegacyProxiesScanner {
  sealed trait LPSMessage
  case class ScanBucket(tgt: ScanTarget) extends LPSMessage
  case class CheckTableReady(nextMsg: LPSMessage) extends LPSMessage
  case class CheckTableReadyPrms(completionPromise: Promise[Boolean]) extends LPSMessage

  sealed trait LPSError
  case object WrongTableState extends LPSError
  case class ProviderError(err:Throwable) extends LPSError
}

class LegacyProxiesScanner @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager, s3ClientMgr:S3ClientManager,
                                     esClientMgr:ESClientManager, scanTargetDAO: ScanTargetDAO, injector:Injector)(implicit system:ActorSystem)extends Actor {
  import LegacyProxiesScanner._
  private val logger = Logger(getClass)
  implicit val mat = ActorMaterializer.create(system)

  implicit val ec:ExecutionContext = system.dispatcher

  private val ddbClient = ddbClientMgr.getNewDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  private var tableReadyTimer:Option[Cancellable] = None

  /**
    * initiates an update to the table's provisioned capacity.
    * @param boostTo provisioned write capacity to boost to
    * @param tgt [[ScanTarget]]
    * @return a Boolean indicating whether you need to wait for the table to be ready.  If false, then proceed immediately.
    *         If true then return; [[CheckTableReady]] will be dispatched at regular intervals to check whether the table is ready.
    *         Once it is then ScanBucket will be dispatched again with the [[ScanTarget]] provided in the `tgt` argument
    */
  def updateProvisionedWriteCapacity(boostTo: Int, tgt:ScanTarget, completionPromise:Option[Promise[Boolean]]):Either[LPSError, Boolean] = {
    val tableName = config.get[String]("proxies.tableName")
    val result = ddbClient.describeTable(config.get[String]("proxies.tableName"))

    if(result.getTable.getTableStatus!="ACTIVE"){
      logger.warn(s"Can't update table status while it is in ${result.getTable.getTableStatus} state.")
      Left(WrongTableState)
    } else {
      val tp = result.getTable.getProvisionedThroughput
      if (tp.getWriteCapacityUnits == boostTo) {
        Right(false)
      } else {
        val msgToSend = completionPromise match {
          case Some(promise) => CheckTableReadyPrms(promise)
          case None => CheckTableReady(ScanBucket(tgt))
        }
        try {
          ddbClient.updateTable(config.get[String]("proxies.tableName"),
            new ProvisionedThroughput()
              .withReadCapacityUnits(tp.getReadCapacityUnits)
              .withWriteCapacityUnits(boostTo.toLong))
          tableReadyTimer = Some(system.scheduler.schedule(10 seconds, 1 second, self, msgToSend))
          Right(true)
        } catch {
          case ex:Throwable=>Left(ProviderError(ex))
        }
      }
    }
  }

  /**
    * call out to DynamoDB to get the current table status
    * @return
    */
  def isTableReady = {
    logger.info(s"Checking if table is ready...")
    val result = ddbClient.describeTable(config.get[String]("proxies.tableName"))

    result.getTable.getTableStatus match {
      case "ACTIVE"=> //update has completed
        logger.info("Table has re-entered ACTIVE state")
        tableReadyTimer match {
          case None=>
            logger.error("Processing CheckTableReady with no active timer? This is a bug")
            false
          case Some(tmr)=>
            tmr.cancel()
            tableReadyTimer = None
            true
        }
      case "UPDATING"=>
        logger.info("Table is still in UPDATING state")
        false
    }
  }

  override def receive: Receive = {
    case ScanBucket(tgt)=>
      logger.info("Boosting provisioned write capacity to 150...")
      //updateProvisionedWriteCapacity returns False if it needs to update capacity.  We will get re-dispatched by the scheduler in
      //that case
      val canStart = updateProvisionedWriteCapacity(150,tgt,None) match {
        case Left(WrongTableState)=>
          logger.error("Table is not in the right state, can't start a scan.")
          false
        case Left(ProviderError(err))=>
          logger.error("AWS returned an error, can't update provisioned capacity", err)
          false
        case Right(flag)=>flag
      }

      if(canStart) {
        logger.info(s"Starting proxy scan on $tgt")
        val client = s3ClientMgr.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))

        val keySource = client.listBucket(tgt.proxyBucket, None)
        val ddbSink = injector.getInstance(classOf[DDBSink])
        val streamCompletionPromise = Promise[Unit]()
        val eosDetect = new EOSDetect[Unit, ProxyLocation](streamCompletionPromise, ())
        val converter = new S3ToProxyLocationFlow(s3ClientMgr, config, tgt.bucketName, Seq())

        keySource.via(converter).log("legacy-proxies-scanner").via(eosDetect).to(ddbSink).run()

        streamCompletionPromise.future.onComplete({
          case Success(_)=>
            logger.info("Scan bucket completed, reverting provisioned write capacity...")
            val updateCapacityPromise = Promise[Boolean]
            updateProvisionedWriteCapacity(4,tgt,Some(updateCapacityPromise))
            updateCapacityPromise.future.onComplete({
              case Success(_)=>
                logger.info("Successfully reduced provisioned write capacity.")
              case Failure(err)=>
                logger.error("Could not reduce provisioned write capacity: ", err)
            })
          case Failure(err)=>
            logger.error("Stream completion failed, this is not expected", err)
        })
      }

    case CheckTableReady(nextMsg:LPSMessage)=>
      if(isTableReady){
        logger.info(s"Re-sending $nextMsg")
        self ! nextMsg
      }
    case CheckTableReadyPrms(completionPromise)=>
      if(isTableReady){
        completionPromise.complete(Success(true))
      }
  }

}
