package services

import java.time.ZonedDateTime

import akka.actor.{Actor, ActorSystem}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.sksamuel.elastic4s.http.bulk.BulkResponseItem
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager}
import com.theguardian.multimedia.archivehunter.common._
import javax.inject.Inject
import play.api.{Configuration, Logger}
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.streams.{RequestBuilder, ResponseListener, SubscriberConfig}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModelDAO, JobStatus}
import helpers.{EOSDetect, ProxyLocatorFlow, ProxyVerifyFlow, SearchHitToArchiveEntryFlow}
import models.IndexUpdateCounter
import play.api.inject.Injector

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.duration._

object ProxiesRelinker {
  trait RelinkMsg

  trait RelinkReply

  case class RelinkRequest(fileId:String) extends RelinkMsg
  case class RelinkAllRequest(jobId:String) extends RelinkMsg

  case class RelinkSuccess(indexUpdateCounter: IndexUpdateCounter) extends RelinkReply
  case class RelinkError(err:Throwable) extends RelinkReply
}

class ProxiesRelinker @Inject() (config:Configuration,
                                 esClientMgr:ESClientManager, ddbClientMgr:DynamoClientManager, system:ActorSystem,
                                 proxyVerifyFlow: ProxyVerifyFlow, jobModelDAO:JobModelDAO)  extends Actor with ArchiveEntryRequestBuilder {
  import ProxiesRelinker._
  import com.sksamuel.elastic4s.http.ElasticDsl._

  private val logger = Logger(getClass)
  override val indexName = config.get[String]("externalData.indexName")
  private val indexer = new Indexer(indexName)
  private implicit val mat:Materializer = ActorMaterializer.create(system)
  private val esClient = esClientMgr.getClient()

  protected def getIndexScanSource = {
    val pub = esClient.publisher(search(indexName) query boolQuery().must(termQuery("proxied", false)) scroll "1m")
    Source.fromPublisher(pub)
  }

  protected def getIndexUpdateSink(completionPromise:Promise[IndexUpdateCounter]) = {
    var ackCounter = 0
    var errCounter = 0

    val esSubscriberConfig = SubscriberConfig[ArchiveEntry](listener = new ResponseListener[ArchiveEntry] {
      override def onAck(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.debug(s"ES subscriber ACK: ${resp.toString} for $original")
        ackCounter+=1
      }

      override def onFailure(resp: BulkResponseItem, original: ArchiveEntry): Unit = {
        logger.error(s"ES subscriber failed: ${resp.error} ${resp.result}")
        errCounter += 1
      }
    },batchSize=100,concurrentRequests=5,completionFn = ()=>{
      logger.warn("Index update sink completed")
      //the promise may have already been completed by the errorFn below
      if(!completionPromise.isCompleted) completionPromise.complete(Success(IndexUpdateCounter(ackCounter, errCounter)))
    },errorFn = (err:Throwable)=>{
      logger.error("Could not send to elasticsearch", err)
      completionPromise.failure(err)
    },failureWait= 5.seconds, maxAttempts=10
    )

    val sub = esClient.subscriber[ArchiveEntry]()
    Sink.fromSubscriber(sub)
  }

  private def globalRelinkScan(jobId:String) = {
    val completionPromise = Promise[IndexUpdateCounter]()
    val eosPromise = Promise[Unit]()

    logger.info("Starting global relink scan")
    val eosDetect = new EOSDetect[Unit, ArchiveEntry](eosPromise, ())
    getIndexScanSource
      .via(new SearchHitToArchiveEntryFlow)
      .via(proxyVerifyFlow)
      .via(eosDetect)
      .to(getIndexUpdateSink(completionPromise))
      .run()

    val originalSender = sender()

    eosPromise.future.onComplete({
      case Success(counter)=>
        logger.info(s"Global relink scan completed - detected via EOS")
        jobModelDAO.jobForId(jobId).map({
          case None=>
            logger.error(s"Proxy relink job record must have been deleted while we were running! Job completed but can't record.")
          case Some(Right(jobModel))=>
            val updatedJob = jobModel.copy(completedAt = Some(ZonedDateTime.now()),jobStatus = JobStatus.ST_SUCCESS)
            jobModelDAO.putJob(updatedJob)
        })
        originalSender ! RelinkSuccess(IndexUpdateCounter(-1,-1))
      case Failure(err)=>
        logger.error("Global relink scan failed with error: ",err)
        jobModelDAO.jobForId(jobId).map({
          case None=>
            logger.error(s"Proxy relink job record must have been deleted while we were running! Job completed but can't record.")
          case Some(Right(jobModel))=>
            val updatedJob = jobModel.copy(completedAt = Some(ZonedDateTime.now()),jobStatus = JobStatus.ST_ERROR, log = Some(err.toString))
            jobModelDAO.putJob(updatedJob)
        })
        originalSender ! RelinkError(err)
    })

    completionPromise.future.onComplete({
      case Success(counter)=>
        logger.info(s"Global relink scan completed with ${counter.ackCount} successful and ${counter.errorCount} failed operations")
        jobModelDAO.jobForId(jobId).map({
          case None=>
            logger.error(s"Proxy relink job record must have been deleted while we were running! Job completed but can't record.")
          case Some(Right(jobModel))=>
            val updatedJob = jobModel.copy(completedAt = Some(ZonedDateTime.now()),jobStatus = JobStatus.ST_SUCCESS)
            jobModelDAO.putJob(updatedJob)
        })
        originalSender ! RelinkSuccess(counter)
      case Failure(err)=>
        logger.error("Global relink scan failed with error: ",err)
        jobModelDAO.jobForId(jobId).map({
          case None=>
            logger.error(s"Proxy relink job record must have been deleted while we were running! Job completed but can't record.")
          case Some(Right(jobModel))=>
            val updatedJob = jobModel.copy(completedAt = Some(ZonedDateTime.now()),jobStatus = JobStatus.ST_ERROR, log = Some(err.toString))
            jobModelDAO.putJob(updatedJob)
        })
        originalSender ! RelinkError(err)
    })
  }

  override def receive: Receive = {
    case RelinkAllRequest(jobId)=>
      jobModelDAO.jobForId(jobId).map({
        case None=>
          logger.error("ProxiesRelinker was sent an invalid job ID, can't continue.")
        case Some(Left(err))=>
          logger.error(s"Could not retrieve job entry from database: $err, can't continue")
        case Some(Right(jobModel))=>
          val updatedJob = jobModel.copy(startedAt = Some(ZonedDateTime.now()),jobStatus=JobStatus.ST_RUNNING)
          jobModelDAO.putJob(updatedJob).onComplete({
            case Success(None)=>
              globalRelinkScan(jobId)
            case Success(Some(Right(updatedJobModel)))=>
              globalRelinkScan(jobId)
            case Success(Some(Left(err)))=>
              logger.error(s"Could not update database: $err")
          })
      })

  }
}
