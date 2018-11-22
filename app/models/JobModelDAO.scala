package models

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import clientManagers.DynamoClientManager
import com.gu.scanamo.{ScanamoAlpakka, Table}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math._
import io.circe.generic.auto._
import io.circe.syntax._
import com.gu.scanamo.syntax._
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import helpers.ZonedTimeFormat
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

@Singleton
class JobModelDAO @Inject() (config:Configuration, ddbClientMgr: DynamoClientManager)(implicit actorSystem:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with JobModelEncoder {
  import com.gu.scanamo.syntax._

  private val logger = Logger(getClass)
  val table = Table[JobModel](config.get[String]("externalData.jobTable"))
  val maxRetries = config.getOptional[Int]("externalData.maxRetries").getOrElse(10)
  val initialRetryDelay = config.getOptional[Int]("externalData.initialRetryDelay").getOrElse(2)
  val retryDelayFactor = config.getOptional[Double]("externalData.retryDelayFactor").getOrElse(1.5)

  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")

  private val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

  def jobForId(jobId:String) = ScanamoAlpakka.exec(ddbClient)(table.get('jobId->jobId))
  def putJob(jobData:JobModel) = ScanamoAlpakka.exec(ddbClient)(table.put(jobData))

}