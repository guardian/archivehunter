package services

import akka.actor.{Actor, ActorSystem, Cancellable}
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{ActorMaterializer, KillSwitches}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.google.inject.Injector
import com.theguardian.multimedia.archivehunter.common.ProxyLocation
import helpers._

import javax.inject.{Inject, Singleton}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ScanTarget, ScanTargetDAO}
import play.api.{Configuration, Logger}
import software.amazon.awssdk.services.dynamodb.model.{DescribeTableRequest, GlobalSecondaryIndexUpdate, ProvisionedThroughput, UpdateGlobalSecondaryIndexAction, UpdateTableRequest}

import scala.concurrent.{ExecutionContext, Future, Promise}
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

@Singleton
class LegacyProxiesScanner @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager, s3ClientMgr:S3ClientManager,
                                     esClientMgr:ESClientManager, scanTargetDAO: ScanTargetDAO, injector:Injector)(implicit system:ActorSystem)extends Actor {
  import LegacyProxiesScanner._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.http.ElasticDsl._

  private val logger = Logger(getClass)
  implicit val mat = ActorMaterializer.create(system)
  val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("archivehunter")
  val tableName = config.get[String]("proxies.tableName")

  implicit val ec:ExecutionContext = system.dispatcher

  private val ddbClient = ddbClientMgr.getNewDynamoClient(config.getOptional[String]("externalData.awsProfile"))

  private var tableReadyTimer:Option[Cancellable] = None

  val updatingCapacityTarget = 300

  /**
    * initiates an update to the table's provisioned capacity.
    * @param boostTo provisioned write capacity to boost to
    * @param tgt [[ScanTarget]]
    * @return a Boolean indicating whether you need to wait for the table to be ready.  If true, then proceed immediately.
    *         If false then return; [[CheckTableReady]] will be dispatched at regular intervals to check whether the table is ready.
    *         Once it is then ScanBucket will be dispatched again with the [[ScanTarget]] provided in the `tgt` argument
    */
  def updateProvisionedWriteCapacity(boostTo: Int, tgt:ScanTarget, completionPromise:Option[Promise[Boolean]]):Either[LPSError, Boolean] = {
    val rq = DescribeTableRequest.builder().tableName(config.get[String]("proxies.tableName")).build()
    val result = ddbClient.describeTable(rq)

    if(result.table().tableStatusAsString()!="ACTIVE"){
      logger.warn(s"Can't update table status while it is in ${result.table().tableStatusAsString()} state.")
      Left(WrongTableState)
    } else {
      val tableThroughput = result.table().provisionedThroughput()
      if(tableThroughput.readCapacityUnits()==0){  //we are not in provisioned mode
        return Right(true)
      }
      val indexName = result.table().globalSecondaryIndexes().get(0).indexName()

      val indexThroughput = result.table().globalSecondaryIndexes().get(0).provisionedThroughput()
      logger.info(s"index name is $indexName, throughput is $indexThroughput")

      if (tableThroughput.writeCapacityUnits() == boostTo && indexThroughput.writeCapacityUnits()==boostTo) {
        Right(true)
      } else {
        val msgToSend = completionPromise match {
          case Some(promise) => CheckTableReadyPrms(promise)
          case None => CheckTableReady(ScanBucket(tgt))
        }
        try {
          val newTableThroughput = ProvisionedThroughput.builder()
            .writeCapacityUnits(boostTo.toLong)
            .readCapacityUnits(tableThroughput.readCapacityUnits())
            .build()
          val newIndexThroughput = ProvisionedThroughput.builder()
            .writeCapacityUnits(boostTo.toLong)
            .readCapacityUnits(10L)
            .build()

          val initialRq = UpdateTableRequest.builder()
            .tableName(tableName)

          val tableRq = if(tableThroughput.writeCapacityUnits() == boostTo){
            initialRq
          } else {
            initialRq.provisionedThroughput(newTableThroughput)
          }

          val indexRq = if(indexThroughput.writeCapacityUnits()==boostTo){
            tableRq
          } else {
            tableRq.globalSecondaryIndexUpdates(GlobalSecondaryIndexUpdate.builder()
              .update(UpdateGlobalSecondaryIndexAction.builder()
                .indexName(indexName)
                .provisionedThroughput(newIndexThroughput)
                .build()
              ).build()
            )
          }

          ddbClient.updateTable(indexRq.build()) //this raises if it fails, caught just below.
          tableReadyTimer = Some(system.scheduler.scheduleAtFixedRate(10 seconds, 1 second, self, msgToSend))
          Right(false)
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
    val rq = DescribeTableRequest.builder()
      .tableName(config.get[String]("proxies.tableName"))
      .build()
    val result = ddbClient.describeTable(rq)

    result.table().tableStatusAsString() match {
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
      logger.info(s"Boosting provisioned write capacity to $updatingCapacityTarget...")
      //updateProvisionedWriteCapacity returns False if it needs to update capacity.  We will get re-dispatched by the scheduler in
      //that case
      val canStart = updateProvisionedWriteCapacity(updatingCapacityTarget,tgt,None) match {
        case Left(WrongTableState)=>
          logger.error("Table is not in the right state, can't start a scan.")
          false
        case Left(ProviderError(err))=>
          logger.error("AWS returned an error, can't update provisioned capacity", err)
          false
        case Right(flag)=>flag
      }
      logger.debug(s"canStart: $canStart")

      if(canStart) {
        logger.info(s"Starting proxy scan on $tgt")
        val esClient = esClientMgr.getClient()
//        val client = s3ClientMgr.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))
//
//        val keySource = client.listBucket(tgt.proxyBucket, None)
        val searchHitPublisher = esClient.publisher(search(indexName) matchQuery ("bucket.keyword", tgt.bucketName) scroll "1m")
        val searchHitSource = Source.fromPublisher(searchHitPublisher)
        val archiveEntryConverter = new SearchHitToArchiveEntryFlow
        val proxyLocator = injector.getInstance(classOf[ProxyLocatorFlow])
        val ddbSink = injector.getInstance(classOf[DDBSink])
        val streamCompletionPromise = Promise[Unit]()
        val eosDetect = new EOSDetect[Unit, ProxyLocation](streamCompletionPromise, ())

        //keySource.via(converter).log("legacy-proxies-scanner").via(eosDetect).to(ddbSink).run()
        searchHitSource
          .via(archiveEntryConverter.async)
          .via(proxyLocator.async)
          .via(eosDetect)
          .log("proxies-scanner")
          .to(ddbSink)
          .run()

        streamCompletionPromise.future.onComplete({
          case Success(_)=>
            logger.info("Scan bucket completed, reverting provisioned write capacity...")
            val updateCapacityPromise = Promise[Boolean]()
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
      logger.debug("checkTableReady")
      if(isTableReady){
        logger.info(s"Re-sending $nextMsg")
        self ! nextMsg
      }
    case CheckTableReadyPrms(completionPromise)=>
      logger.debug("checkTableReadyPrms")
      if(isTableReady){
        completionPromise.complete(Success(true))
      }
  }

}
