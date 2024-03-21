package services

import java.time.ZonedDateTime
import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Timers}
import akka.stream.scaladsl.{FlowOps, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream._
import akka.stream.alpakka.s3.{S3Attributes, S3Ext, S3Settings}
import akka.stream.alpakka.s3.scaladsl.S3
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.amazonaws.regions.{Region, Regions}
import com.google.inject.Injector
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient}
import com.sksamuel.elastic4s.http.bulk.BulkResponseItem
import helpers._

import javax.inject.{Inject, Singleton}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import play.api.{Configuration, Logger}
import com.sksamuel.elastic4s.streams.{RequestBuilder, ResponseListener, SubscriberConfig}
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryRequestBuilder}
import software.amazon.awssdk.regions
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object BucketScanner {
  trait BSMsg

  case object TickKey

  case class ScanTargetsUpdated() extends BSMsg
  case class PerformTargetScan(record:ScanTarget, maybeJob:Option[JobModel]=None) extends BSMsg
  case class PerformDeletionScan(record:ScanTarget, thenScanForNew: Boolean=false, maybeJob:Option[JobModel]=None) extends BSMsg
  case object RegularScanTrigger extends BSMsg
}

@Singleton
class BucketScanner @Inject()(override val config:Configuration, ddbClientMgr:DynamoClientManager, s3ClientMgr:S3ClientManager,
                              esClientMgr:ESClientManager, scanTargetDAO: ScanTargetDAO, jobModelDAO:JobModelDAO,
                              injector:Injector)(implicit system:ActorSystem, mat:Materializer)
  extends Actor with BucketScannerFunctions with ZonedTimeFormat with ArchiveEntryRequestBuilder{
  import BucketScanner._

  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  protected val logger=Logger(getClass)

  implicit val ec:ExecutionContext = system.dispatcher

  override val indexName: String = config.get[String]("externalData.indexName")

  def listScanTargets() =
    scanTargetDAO.allScanTargets().map(result=>{
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

  /**
    * trigger a scan, if one is due, by messaging ourself
    * @param scanTarget [[ScanTarget]] instance to check
    * @return boolean indicating whether a scan was triggered
    */
  def maybeTriggerScan(scanTarget:ScanTarget):Boolean = {
    if(!scanTarget.enabled){
      logger.info(s"Not scanning ${scanTarget.bucketName} as it is disabled")
      false
    } else if(scanIsInProgress(scanTarget)){
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

  protected def getElasticSearchSink(esclient:ElasticClient, completionPromise:Promise[Unit]) = {
    val esSubscriberConfig = SubscriberConfig[ArchiveEntry](listener = new ResponseListener[ArchiveEntry] {
      override def onAck(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.debug(s"ES subscriber ACK: ${resp.toString} for $original")
      }

      override def onFailure(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.error(s"ES subscriber failed: ${resp.error} ${resp.result}")
      }
    },batchSize=100,concurrentRequests=5,completionFn = ()=>{
      //the promise may have already been completed by the errorFn below
      if(!completionPromise.isCompleted) completionPromise.complete(Success(()))
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

  private implicit class ExtendedFlowOps[+Out, +Mat](op: FlowOps[Out, Mat]) {
    //Define a  private extension method so that we can factor our this rather cumbersome code for re-use
    def withS3Region(rgn:String):op.Repr[Out] = op.withAttributes(
      Attributes(
        S3Attributes.settings(
          S3Settings().withS3RegionProvider(new AwsRegionProvider {
            override def getRegion: regions.Region = regions.Region.of(rgn)
          })
        ).attributeList
      )
    )
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
    val esclient = esClientMgr.getClient()

    val keySource = S3.listBucket(target.bucketName, None).withAttributes(
      Attributes(
        S3Attributes.settings(
          S3Settings().withS3RegionProvider(new AwsRegionProvider {
            override def getRegion: regions.Region = regions.Region.of(target.region)
          })
        ).attributeList
      )
    )//.withS3Region(target.region)

    val converterFlow = injector.getInstance(classOf[S3ToArchiveEntryFlow])

    val indexSink = getElasticSearchSink(esclient, completionPromise)

    val properCredentials = s3ClientMgr.getAlpakkaCredentials(config.getOptional[String]("externalData.awsProfile"))

    keySource
      .withAttributes(S3Attributes.settings(properCredentials))
      .via(converterFlow).named(target.region).log("S3ToArchiveEntryFlow").to(indexSink).run()

    completionPromise
  }

  /**
    * Performs a scan in "paranoid" mode, i.e. assume that S3 will return invalid XML that will break the standard SDK (it does sometimes....)
    * @param target [[ScanTarget]] indicating bucket to process
    * @return a Promise[Unit] which completes when the scan finishes
    */
  def doScanParanoid(target:ScanTarget):Promise[Unit] = {
    logger.warn(s"Configured to do paranoid scan on $target")

    val region = Region.getRegion(Regions.fromName(target.region))

    val completionPromise = Promise[Unit]() //this promise will get fulfilled when the stream ends.

    val esclient = esClientMgr.getClient()

    val source = new ParanoidS3Source(target.bucketName,region, s3ClientMgr.newCredentialsProvider(config.getOptional[String]("externalData.awsProfile")))
    val processor = new S3XMLProcessor()
    val converterFlow = injector.getInstance(classOf[S3ToArchiveEntryFlow]).named(target.region)

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

    val esclient = esClientMgr.getClient()

    val esSource = Source.fromPublisher(esclient.publisher(search(indexName) query s"bucket.keyword:${target.bucketName} AND beenDeleted:false" scroll "1m"))

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
    case PerformTargetScan(tgt, maybeJob)=>
      scanTargetDAO.setInProgress(tgt, newValue=true).flatMap(updatedScanTarget=> {
        val promise = tgt.paranoid match {
          case Some(value) =>
            if (value) doScanParanoid(tgt) else doScan(tgt)
          case None => doScan(tgt)
        }

        promise.future.andThen({
          case Success(x) =>
            maybeJob.map(job=>{
              val updatedJob = job.copy(completedAt=Some(ZonedDateTime.now()), jobStatus = JobStatus.ST_SUCCESS)
              jobModelDAO.putJob(updatedJob)
            })
            scanTargetDAO.setScanCompleted(updatedScanTarget)
          case Failure(err) =>
            maybeJob.map(job=>{
              val updatedJob = job.copy(completedAt=Some(ZonedDateTime.now()), jobStatus = JobStatus.ST_ERROR, log=Some(err.toString))
              jobModelDAO.putJob(updatedJob)
            })
            scanTargetDAO.setScanCompleted(updatedScanTarget, error = Some(err))
        })
      }).onComplete({
        case Success(result)=>
          logger.info(s"Completed addition scan of ${tgt.bucketName}")
        case Failure(err)=>
          logger.error(s"Could not scan ${tgt.bucketName}: ", err)
      })
    case PerformDeletionScan(tgt, thenScanForNew, maybeJob)=>
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
            //don't complete the job here, it's done in PerformTargetScan
          } else {
            //PerformTargetScan not happening, need to complete the job here
            maybeJob.map(job=>{
              val updatedJob = job.copy(completedAt=Some(ZonedDateTime.now()), jobStatus = JobStatus.ST_SUCCESS)
              jobModelDAO.putJob(updatedJob)
            })
          }
        case Failure(err)=>
          logger.error(s"Could not scan ${tgt.bucketName}", err)
          maybeJob.map(job=>{
            val updatedJob = job.copy(completedAt=Some(ZonedDateTime.now()), jobStatus = JobStatus.ST_ERROR, log=Some(err.toString))
            jobModelDAO.putJob(updatedJob)
          })
      })
  }
}
