package services

import java.time.ZonedDateTime
import java.time.temporal.{ChronoUnit, IsoFields, TemporalUnit}

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Timers}
import akka.http.scaladsl.Http
import akka.stream.scaladsl.{GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, ClosedShape, KillSwitches, Materializer}
import clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.amazonaws.regions.{Region, Regions}
import com.google.inject.Injector
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.sksamuel.elastic4s.http.HttpClient
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
  case class PerformDeletionScan(record:ScanTarget, thenScanForNew: Boolean=false) extends BSMsg
  case object RegularScanTrigger extends BSMsg
}


class BucketScanner @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager, s3ClientMgr:S3ClientManager,
                              esClientMgr:ESClientManager, scanTargetDAO: ScanTargetDAO, injector:Injector)(implicit system:ActorSystem)
  extends Actor with Timers with ZonedTimeFormat with ArchiveEntryRequestBuilder{
  import BucketScanner._

  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

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
        logger.info(s"${target.bucketName}: Next scan is due at ${lastScanned.plus(target.scanInterval,ChronoUnit.SECONDS)}")
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
        self ! PerformDeletionScan(scanTarget, thenScanForNew = true)
        true
      } else {
        logger.info(s"Not scanning ${scanTarget.bucketName} as it is not due yet")
        false
      }
    }
  }

  protected def getElasticSearchSink(esclient:HttpClient, completionPromise:Promise[Unit]) = {
    val esSubscriberConfig = SubscriberConfig[ArchiveEntry](listener = new ResponseListener[ArchiveEntry] {
      override def onAck(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.debug(s"ES subscriber ACK: ${resp.toString} for $original")
      }

      override def onFailure(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.debug(s"ES subscriber failed on $original")
      }
    },batchSize=100,concurrentRequests=5,completionFn = ()=>{
      //the promise may have already been completed by the errorFn below
      if(!completionPromise.isCompleted) completionPromise.complete(Success())
      ()
    },errorFn = (err:Throwable)=>{
      logger.error("Could not send to elasticsearch", err)
      completionPromise.failure(err)
      ()
    },failureWait= 5.seconds, maxAttempts=10
    )

    val subscriber = esclient.subscriber[ArchiveEntry](esSubscriberConfig)

    Sink.fromSubscriber(subscriber)
  }

  /**
    * Performs a scan of the given target.  The scan is done asynchronously using Akka streaming, so this method returns a Promise
    * which completes when the scan is done.
    * @param target [[ScanTarget]] indicating bucket to process
    * @return a Promise[Unit] which completes when the scan finishes
    */
  def doScan(target: ScanTarget) = {
    val completionPromise = Promise[Unit]()

    logger.info(s"Started scan for $target")
    val client = s3ClientMgr.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))
    val esclient = esClientMgr.getClient()

    val keySource = client.listBucket(target.bucketName, None)
    val converterFlow = injector.getInstance(classOf[S3ToArchiveEntryFlow])

    val indexSink = getElasticSearchSink(esclient, completionPromise)

    keySource.via(converterFlow).log("S3ToArchiveEntryFlow").to(indexSink).run()

    completionPromise
  }

  /**
    * Performs a scan in "paranoid" mode, i.e. assume that S3 will return invalid XML that will break the standard SDK (it does sometimes....)
    * @param target [[ScanTarget]] indicating bucket to process
    * @return a Promise[Unit] which completes when the scan finishes
    */
  def doScanParanoid(target:ScanTarget):Promise[Unit] = {
    logger.warn(s"Configured to do paranoid scan on $target")
    val region = Region.getRegion(Regions.fromName(config.get[String]("externalData.awsRegion")))
//    val hostname = s"s3-$region.amazonaws.com"
//    val urlString = s"https://$hostname/${target.bucketName}"
//
    val completionPromise = Promise[Unit]() //this promise will get fulfilled when the stream ends.

    val esclient = esClientMgr.getClient()

    val source = new ParanoidS3Source(target.bucketName,region, s3ClientMgr.credentialsProvider(config.getOptional[String]("externalData.awsProfile")))(system)
    val processor = new S3XMLProcessor()
    val converterFlow = injector.getInstance(classOf[S3ToArchiveEntryFlow])

    val indexSink = getElasticSearchSink(esclient, completionPromise)

    val graph = RunnableGraph.fromGraph(GraphDSL.create(){ implicit builder:GraphDSL.Builder[NotUsed]=>
      import GraphDSL.Implicits._
      val src = builder.add(source)
      val proc = builder.add(processor)
      val converter = builder.add(converterFlow)
      val sink = builder.add(indexSink)

      src ~> proc ~> converter ~> sink
      ClosedShape
    })

    graph.run()

    completionPromise
  }

  def doScanDeleted(target:ScanTarget):Promise[Unit] = {
    val completionPromise = Promise[Unit]()

    val client = s3ClientMgr.getAlpakkaS3Client(config.getOptional[String]("externalData.awsProfile"))
    val esclient = esClientMgr.getClient()

    val esSource = Source.fromPublisher(esclient.publisher(search(indexName) query s"bucket:${target.bucketName} AND beenDeleted:false" scroll "1m"))

    val verifyFlow = injector.getInstance(classOf[ArchiveEntryVerifyFlow])
    val indexSink = getElasticSearchSink(esclient, completionPromise)

    val killSwitch = esSource.via(new SearchHitToArchiveEntryFlow).via(verifyFlow).log("ArchiveEntryVerifyFlow").viaMat(KillSwitches.single)(Keep.right).to(indexSink).run()

    completionPromise.future.onComplete({
      case Success(_)=>logger.info("Deletion scan completed")
      case Failure(err)=>
        logger.warn("Scan failure detected, shutting down pipeline")
        killSwitch.abort(err)
    })
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
      scanTargetDAO.setInProgress(tgt, newValue=true).flatMap(updatedScanTarget=> {
        val promise = tgt.paranoid match {
          case Some(value) =>
            if (value) doScanParanoid(tgt) else doScan(tgt)
          case None => doScan(tgt)
        }

        promise.future.andThen({
          case Success(x) =>
            scanTargetDAO.setScanCompleted(updatedScanTarget)
          case Failure(err) =>
            scanTargetDAO.setScanCompleted(updatedScanTarget, error = Some(err))
        })
      }).onComplete({
        case Success(result)=>
          logger.info(s"Completed addition scan of ${tgt.bucketName}")
        case Failure(err)=>
          logger.error(s"Could not scan ${tgt.bucketName}: ", err)
      })
    case PerformDeletionScan(tgt, thenScanForNew)=>
      scanTargetDAO.setInProgress(tgt, newValue = true).flatMap(updatedScanTarget=>
        doScanDeleted(updatedScanTarget).future.andThen({
          case Success(_)=>
            scanTargetDAO.setScanCompleted(updatedScanTarget)
          case Failure(err)=>
            scanTargetDAO.setScanCompleted(updatedScanTarget, error=Some(err))
        })
      ).onComplete({
        case Success(result)=>
          logger.info(s"Completed deletion scan of ${tgt.bucketName}")
          if(thenScanForNew){
            logger.info(s"Scheduling scan for new items in ${tgt.bucketName}")
            self ! PerformTargetScan(tgt)
          }
        case Failure(err)=>
          logger.error(s"Could not scan ${tgt.bucketName}", err)
      })
  }
}
