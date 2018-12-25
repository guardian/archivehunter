package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo.error.DynamoReadError
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.gu.scanamo.{ScanamoAlpakka, Table}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math._
import io.circe.generic.auto._
import io.circe.syntax._
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters, ZonedDateTimeEncoder}
import javax.inject.{Inject, Singleton}
import org.apache.logging.log4j.LogManager

@Singleton
class JobModelDAO @Inject()(config:ArchiveHunterConfiguration, ddbClientMgr: DynamoClientManager)(implicit actorSystem:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with JobModelEncoder with ExtValueConverters {
  import com.gu.scanamo.syntax._

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

  private val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

  def jobForId(jobId:String) = ScanamoAlpakka.exec(ddbClient)(table.get('jobId->jobId))
  def putJob(jobData:JobModel) = ScanamoAlpakka.exec(ddbClient)(table.put(jobData))

  protected def optionalTimeQuery(startingTime:Option[ZonedDateTime]=None, endingTime:Option[ZonedDateTime]=None)(block: (Option[String],String)=>Future[List[Either[DynamoReadError, JobModel]]]) ={
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
          case Left(err)=>true
        }))
      case None=>resultFuture
    }
  }

  def jobsForSource(sourceId:String, startingTime:Option[ZonedDateTime]=None, endingTime:Option[ZonedDateTime]=None,limit:Int=100) =
    optionalTimeQuery(startingTime, endingTime) { (maybeStartTimeString, currentTimeString)=>
      maybeStartTimeString match {
        case Some(startTimeString)=>
          ScanamoAlpakka.exec(ddbClient)(sourcesIndex.query(('sourceId->sourceId and ('startedAt between Bounds(Bound(startTimeString), Bound(currentTimeString)))).descending))
        case None=>
          ScanamoAlpakka.exec(ddbClient)(sourcesIndex.query(('sourceId->sourceId).descending))
      }
    }


  def jobsForStatus(status:JobStatus.Value, startingTime:Option[ZonedDateTime]=None, endingTime:Option[ZonedDateTime]=None,limit:Int=100) =
    optionalTimeQuery(startingTime, endingTime) { (maybeStartTimeString, currentTimeString)=>
      maybeStartTimeString match {
        case Some(startTimeString)=>
          ScanamoAlpakka.exec(ddbClient)(jobStatusIndex.limit(limit).query(('jobStatus->status.toString and ('startedAt between Bounds(Bound(startTimeString), Bound(currentTimeString)))).descending))
        case None=>
          ScanamoAlpakka.exec(ddbClient)(jobStatusIndex.limit(limit).query(('jobStatus->status.toString).descending))
      }
    }


  def jobsForType(jobType:String, startingTime:Option[ZonedDateTime]=None, endingTime:Option[ZonedDateTime]=None,limit:Int=100) =
    optionalTimeQuery(startingTime, endingTime) { (maybeStartTimeString, currentTimeString) =>
      maybeStartTimeString match {
        case Some(startTimeString)=>
          ScanamoAlpakka.exec(ddbClient)(jobTypeIndex.query(('jobType->jobType and ('startedAt between Bounds(Bound(startTimeString), Bound(currentTimeString)))).descending))
        case None=>ScanamoAlpakka.exec(ddbClient)(jobTypeIndex.query(('jobType->jobType).descending))
      }
    }


  def allJobs(limit:Int) = ScanamoAlpakka.exec(ddbClient)(table.limit(limit).scan)
  def deleteJob(jobId:String) = ScanamoAlpakka.exec(ddbClient)(table.delete('jobId->jobId))
}