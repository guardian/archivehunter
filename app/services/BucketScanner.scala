package services

import java.time.ZonedDateTime
import java.time.temporal.{ChronoUnit, IsoFields, TemporalUnit}

import akka.actor.{Actor, ActorSystem, Timers}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, Materializer}
import com.google.inject.Injector
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.sksamuel.elastic4s.http.bulk.BulkResponseItem
import helpers._
import javax.inject.Inject
import models.{ScanTarget, ScanTargetDAO}
import play.api.{Configuration, Logger}
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.streams.{RequestBuilder, ResponseListener, SubscriberConfig}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryRequestBuilder}

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object BucketScanner {
  trait BSMsg

  case object TickKey

  case class ScanTargetsUpdated() extends BSMsg
  case class PerformTargetScan(record:ScanTarget) extends BSMsg
  case object RegularScanTrigger extends BSMsg
}


class BucketScanner @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager, s3ClientMgr:S3ClientManager,
                              esClientMgr:ESClientManager, scanTargetDAO: ScanTargetDAO, injector:Injector)(implicit system:ActorSystem)
  extends Actor with Timers with ZonedTimeFormat with ArchiveEntryRequestBuilder{
  import BucketScanner._

  private val logger=Logger(getClass)

  implicit val mat = ActorMaterializer.create(system)
  implicit val ec:ExecutionContext = system.dispatcher

  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))

  override val indexName: String = config.get[String]("externalData.indexName")

  //actor-local timer - https://doc.akka.io/docs/akka/2.5/actors.html#actors-timers
  timers.startPeriodicTimer(TickKey, RegularScanTrigger, Duration(config.get[Long]("scanner.masterSchedule"),SECONDS))

  def listScanTargets() = {
    val alpakkaClient = ddbClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))

    ScanamoAlpakka.exec(alpakkaClient)(table.scan()).map(result=>{
      val errors = result.collect({
        case Left(readError)=>readError
      })

      if(errors.isEmpty){
        result.collect({
          case Right(scanTarget)=>scanTarget
        })
      } else {
        throw new RuntimeException(errors.map(_.toString).mkString(","))
      }
    })
  }

  /**
    * returns a boolean indicating whether the given target is due a scan, i.e. last_scan + scan_interval < now OR
    * not scanned at all
    * @param target [[ScanTarget]] instance to check
    * @return boolean flag
    */
  def scanIsScheduled(target: ScanTarget) = {
    target.lastScanned match {
      case None=>true
      case Some(lastScanned)=>
        lastScanned.plus(target.scanInterval,ChronoUnit.SECONDS).isBefore(ZonedDateTime.now())
    }
  }

  /**
    * trigger a scan, if one is due, by messaging ourself
    * @param scanTarget [[ScanTarget]] instance to check
    * @return boolean indicating whether a scan was triggered
    */
  def maybeTriggerScan(scanTarget:ScanTarget):Boolean = {
    if(!scanTarget.enabled){
      logger.info(s"Not scanning ${scanTarget.bucketName} as it is disabled")
      false
    } else if(scanTarget.scanInProgress){
      logger.info(s"Not scanning ${scanTarget.bucketName} as it is already in progress")
      false
    } else {
      if(scanIsScheduled(scanTarget)){
        self ! PerformTargetScan(scanTarget)
        true
      } else {
        false
      }
    }
  }

  /**
    * Performs a scan of the given target.  The scan is done asynchronously using Akka streaming, so this method returns a Promise
    * which completes when the scan is done.
    * @param target [[ScanTarget]] indicating bucket to process
    * @return a Promise[Unit] which completes when the scan finishes
    */
  def doScan(target: ScanTarget) = {
    val completionPromise = Promise[Unit]()

    val client = s3ClientMgr.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))
    val esclient = esClientMgr.getClient()

    val checkFuture = esclient.client.async("GET","http://localhost:9200/_cat/indices").map(resp=>{
      logger.info(resp.entity.getOrElse("no body returned").toString)
    })

    Await.ready(checkFuture, 10.seconds)

    val keySource = client.listBucket(target.bucketName, None)
    val converterFlow = injector.getInstance(classOf[S3ToArchiveEntryFlow])

    val esSubscriberConfig = SubscriberConfig[ArchiveEntry](listener = new ResponseListener[ArchiveEntry] {
      override def onAck(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.debug(s"ES subscriber ACK: ${resp.toString} for $original")
      }

      override def onFailure(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.debug(s"ES subscriber failed on $original")
      }
    },batchSize=10,concurrentRequests=5,completionFn = ()=>{
              completionPromise.complete(Success())
              ()
            },errorFn = (err:Throwable)=>{
              logger.error("Could not send to elasticsearch", err)
              completionPromise.failure(err)
              ()
            },failureWait= 1.seconds, maxAttempts=1
    )

//    val subscriber = esclient.subscriber[ArchiveEntry](
//      1,1,completionFn = ()=>{
//        completionPromise.complete(Success())
//        ()
//      },errorFn = (err:Throwable)=>{
//        logger.error("Could not send to elasticsearch", err)
//        completionPromise.failure(err)
//        ()
//      },failureWait= 1.seconds, maxAttempts=1
//    )
    val subscriber = esclient.subscriber[ArchiveEntry](esSubscriberConfig)

    val indexSink = Sink.fromSubscriber(subscriber)

    keySource.via(converterFlow).log("S3ToArchiveEntryFlow").to(indexSink).run()

    completionPromise
  }

  override def receive: Receive = {
    case RegularScanTrigger=>
      logger.debug("Regular scan trigger received")
      listScanTargets().map(_.map(tgt=>(tgt,maybeTriggerScan(tgt)))).onComplete({
        case Success(resultList)=>
          logger.info("Scan trigger report:")
          resultList.foreach(result=>logger.info(s"${result._1.bucketName}: ${result._2}"))
        case Failure(err)=>
          logger.error("Could not perform regular scan check", err)
      })
    case PerformTargetScan(tgt)=>
      scanTargetDAO.setInProgress(tgt, newValue=true).flatMap(updatedScanTarget=>
        doScan(tgt).future.andThen({
          case Success(x)=>
            scanTargetDAO.setScanCompleted(updatedScanTarget)
          case Failure(err)=>
            scanTargetDAO.setScanCompleted(updatedScanTarget,error=Some(err))
        })
      ).onComplete({
        case Success(result)=>
          logger.info(s"Completed periodic scan of ${tgt.bucketName}")
        case Failure(err)=>
          logger.error(s"Could not scan ${tgt.bucketName}: ", err)
      })
  }
}
