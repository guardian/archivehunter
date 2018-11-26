package com.theguardian.multimedia.archivehunter.common.cmn_models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
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

  val maxRetries = config.getOptional[Int]("externalData.maxRetries").getOrElse(10)
  val initialRetryDelay = config.getOptional[Int]("externalData.initialRetryDelay").getOrElse(2)
  val retryDelayFactor = config.getOptional[Double]("externalData.retryDelayFactor").getOrElse(1.5)

  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")

  private val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

  def jobForId(jobId:String) = ScanamoAlpakka.exec(ddbClient)(table.get('jobId->jobId))
  def putJob(jobData:JobModel) = ScanamoAlpakka.exec(ddbClient)(table.put(jobData))

  def jobsForSource(sourceId:String) = ScanamoAlpakka.exec(ddbClient)(sourcesIndex.query('sourceId->sourceId))

  def allJobs(limit:Int) = ScanamoAlpakka.exec(ddbClient)(table.limit(limit).scan)
}