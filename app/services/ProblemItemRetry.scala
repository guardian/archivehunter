package services

import java.time.ZonedDateTime

import akka.actor.{Actor, ActorSystem}
import akka.stream.scaladsl.Keep
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProblemItemHitReader, ProblemItemIndexer, ProxyLocationDAO}
import helpers.{CreateProxySink, EOSDetect, ProblemItemReproxySink}
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

import scala.concurrent.Promise
import scala.util.{Failure, Success}

object ProblemItemRetry {
  case class RetryForCollection(collectionName: String)
}

/**
  * this actor sets up an Akka stream to re-run all selected proxies from the ProblemItems index
  * @param config
  * @param proxyGenerators
  * @param esClientManager
  * @param jobModelDAO
  * @param actorSystem
  * @param proxyLocationDAO
  */
@Singleton
class ProblemItemRetry @Inject()(config:Configuration, proxyGenerators:ProxyGenerators,
                                 esClientManager:ESClientManager, jobModelDAO:JobModelDAO,
                                 problemItemReproxySink: ProblemItemReproxySink)
                                (implicit actorSystem: ActorSystem, proxyLocationDAO:ProxyLocationDAO, mat:Materializer) extends Actor with ProblemItemHitReader{
  import ProblemItemRetry._

  private val logger=Logger(getClass)

  private val problemItemIndexName = config.get[String]("externalData.problemItemsIndex")
  private val problemItemIndexer = new ProblemItemIndexer(problemItemIndexName)

  protected implicit val esClient = esClientManager.getClient()
  implicit val ec = actorSystem.dispatcher

  override def receive: Receive = {
    case RetryForCollection(collectionName)=>
      val originalSender = sender()

      val job = JobModel.newJob("ProblemItemRerun",collectionName, SourceType.SRC_SCANTARGET).copy(jobStatus = JobStatus.ST_RUNNING)

      jobModelDAO.putJob(job).map(_=>{
          logger.info(s"Starting problem item scan for $collectionName")
          val completionPromise = Promise[Unit]()
          val detector = new EOSDetect[Unit, ArchiveEntry](completionPromise, ())

          val result = problemItemIndexer.sourceForCollection(collectionName)
            .map(_.to[ProblemItem]).log("ProblemItemRetry")
            .toMat(problemItemReproxySink)(Keep.right).run()

          logger.info(s"Problem item scan underway for $collectionName")

          originalSender ! akka.actor.Status.Success
          result.onComplete({
            case Success(count)=>
              val updatedJob = job.copy(completedAt = Some(ZonedDateTime.now()),jobStatus = JobStatus.ST_SUCCESS)
              jobModelDAO.putJob(updatedJob)
              logger.info(s"Problem item scan completed for $collectionName, with $count results")
            case Failure(err)=>
              val updatedJob = job.copy(completedAt = Some(ZonedDateTime.now()), jobStatus=JobStatus.ST_ERROR, log = Some(err.toString))
              jobModelDAO.putJob(updatedJob)
              logger.error(s"Problem item scan failed for $collectionName: ", err)
          })
      }).recover({
        case err:Throwable=>
          logger.error(s"Could not save job record: $err")
          originalSender ! akka.actor.Status.Failure(new RuntimeException(s"Could not save job record: $err"))
      })
  }
}
