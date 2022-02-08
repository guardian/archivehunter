package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import org.scanamo.{DynamoReadError, ScanamoAlpakka, Table}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math._
import io.circe.generic.auto._
import io.circe.syntax._
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters, ZonedDateTimeEncoder}

import javax.inject.{Inject, Singleton}
import org.apache.logging.log4j.LogManager

@Singleton
class JobModelDAO @Inject()(config:ArchiveHunterConfiguration, ddbClientMgr: DynamoClientManager)(implicit actorSystem:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with JobModelEncoder with ExtValueConverters {

  private val logger = LogManager.getLogger(getClass)

  val table = Table[JobModel](config.get[String]("externalData.jobTable"))
  val sourcesIndex = table.index("sourcesIndex")
  val jobStatusIndex = table.index("jobStatusIndex")
  val jobTypeIndex = table.index("jobTypeIndex")

  val maxRetries = config.getOptional[Int]("externalData.maxRetries").getOrElse(10)
  val initialRetryDelay = config.getOptional[Int]("externalData.initialRetryDelay").getOrElse(2)
  val retryDelayFactor = config.getOptional[Double]("externalData.retryDelayFactor").getOrElse(1.5)

  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")

  private val scanamoAlpakka = ScanamoAlpakka(ddbClientMgr.getNewAsyncDynamoClient(awsProfile))

  def jobForId(jobId:String) = scanamoAlpakka.exec(table.get("jobId"===jobId)).runWith(Sink.head)
  def putJob(jobData:JobModel) = scanamoAlpakka.exec(table.put(jobData)).runWith(Sink.head)

  //less typing and better readability!
  type DynamoJobsResult = List[Either[DynamoReadError, JobModel]]

  protected def optionalTimeQuery(startingTime:Option[ZonedDateTime]=None,
                                  endingTime:Option[ZonedDateTime]=None)
                                 (block: (Option[String],String)=>Future[DynamoJobsResult]) ={
    val currentTimeString = ZonedDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
    val maybeStartTimeString = startingTime.map(_.format(DateTimeFormatter.ISO_DATE_TIME))

    val resultFuture = block(maybeStartTimeString, currentTimeString)

    endingTime match {
      case Some(endTimeValue)=>
        resultFuture.map(_.filter({
          case Right(mdl)=>mdl.completedAt match {
            case Some(completionTime)=>endTimeValue.isAfter(completionTime)
            case None=>false
          }
          case Left(_)=>true
        }))
      case None=>resultFuture
    }
  }

  protected def makeJobSink = Sink.fold[DynamoJobsResult, DynamoJobsResult](List())(_ ++ _)

  def jobsForSource(sourceId:String, startingTime:Option[ZonedDateTime]=None, endingTime:Option[ZonedDateTime]=None, limit:Integer=100) =
    optionalTimeQuery(startingTime, endingTime) { (maybeStartTimeString, currentTimeString)=>
      val alpakkaQuery = maybeStartTimeString match {
        case Some(startTimeString)=>
          //ScanamoAlpakka.exec(ddbClient)(sourcesIndex.query(('sourceId->sourceId and ('startedAt between Bounds(Bound(startTimeString), Bound(currentTimeString)))).descending))
          scanamoAlpakka.exec(sourcesIndex.query("sourceId"===sourceId and ("startedAt" between startTimeString and currentTimeString)))
        case None=>
          //ScanamoAlpakka.exec(ddbClient)(sourcesIndex.query(('sourceId->sourceId).descending))
          scanamoAlpakka.exec(sourcesIndex.query("sourceId"===sourceId))
      }
      alpakkaQuery.take(limit.toLong).runWith(makeJobSink)
    }


  def jobsForStatus(status:JobStatus.Value, startingTime:Option[ZonedDateTime]=None, endingTime:Option[ZonedDateTime]=None, limit:Integer=100) =
    optionalTimeQuery(startingTime, endingTime) { (maybeStartTimeString, currentTimeString)=>
      val alpakkaQuery = maybeStartTimeString match {
        case Some(startTimeString)=>
          //ScanamoAlpakka.exec(ddbClient)(jobStatusIndex.limit(limit).query(('jobStatus->status.toString and ('startedAt between Bounds(Bound(startTimeString), Bound(currentTimeString)))).descending))
          scanamoAlpakka.exec(jobStatusIndex.query("jobStatus"===status.toString and ("startedAt" between startTimeString and currentTimeString)))
        case None=>
          //ScanamoAlpakka.exec(ddbClient)(jobStatusIndex.limit(limit).query(('jobStatus->status.toString).descending))
          scanamoAlpakka.exec(jobStatusIndex.query("jobStatus"===status.toString))
      }
      alpakkaQuery.take(limit.toLong).runWith(makeJobSink)
    }


  def jobsForType(jobType:String, startingTime:Option[ZonedDateTime]=None, endingTime:Option[ZonedDateTime]=None, limit:Integer=100) =
    optionalTimeQuery(startingTime, endingTime) { (maybeStartTimeString, currentTimeString) =>
      val alpakkaQuery = maybeStartTimeString match {
        case Some(startTimeString)=>
          scanamoAlpakka.exec(jobTypeIndex.query("jobType"===jobType and ("startedAt" between startTimeString and currentTimeString)))
        case None=>
          scanamoAlpakka.exec(jobTypeIndex.query("jobType"===jobType))
      }
      alpakkaQuery.take(limit.toLong).runWith(makeJobSink)
    }


  def allJobs(limit:Int) = scanamoAlpakka.exec(table.scan).take(limit.toLong).runWith(makeJobSink)
  def deleteJob(jobId:String) = scanamoAlpakka.exec(table.delete("jobId"===jobId)).runWith(Sink.head)
}