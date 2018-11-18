package models

import java.time.ZonedDateTime

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.gu.scanamo.{Scanamo, Table}
import com.theguardian.multimedia.archivehunter.common.ZonedDateTimeEncoder
import helpers.{DynamoClientManager, ZonedTimeFormat}
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math._
import io.circe.generic.auto._
import io.circe.syntax._

class ScanTargetDAO @Inject() (config:Configuration, ddbClientMgr: DynamoClientManager) extends ZonedDateTimeEncoder with ZonedTimeFormat {
  private val logger = Logger(getClass)
  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))
  val maxRetries = config.getOptional[Int]("externalData.maxRetries").getOrElse(10)
  val initialRetryDelay = config.getOptional[Int]("externalData.initialRetryDelay").getOrElse(2)
  val retryDelayFactor = config.getOptional[Double]("externalData.retryDelayFactor").getOrElse(1.5)

  /**
    * synchronously attempts to make the update, doing exponential delay via sleep until successful.
    * this should be acceptable, since the most likely cause of the operation failing is the DB table running out of provisioned
    * capacity and therefore slowing the app until it's scaled is a good option
    * @param delayTime time to delay this iteration by, if the operation fails
    * @param attempt attempt that we are making
    * @return a Try, with Failure if we have attemped [[maxRetries]] or a [[ScanTarget]] if successful
    */
  private def doNextRetry(updatedRecord:ScanTarget, delayTime:Double, attempt:Int)(implicit client:AmazonDynamoDBAsync):Try[ScanTarget] = {
    Scanamo.exec(client)(table.put(updatedRecord)).map({
      case Left(error)=>
        logger.warn(s"Could not update record on attempt $attempt: ${error.toString}")
        if(attempt<maxRetries){
          Thread.sleep((delayTime*1000.0).toLong)
          doNextRetry(updatedRecord, pow(initialRetryDelay.toDouble,retryDelayFactor*attempt), attempt+1)
        } else {
          logger.error(s"Still can't update record after $attempt attempts, bailing")
          Failure(new RuntimeException(error.toString))
        }
      case Right(scanTarget)=>Success(scanTarget)
    }).getOrElse(Success(updatedRecord))  //return the updated record
  }

  /**
    * Sets or clears the in-progress flag for the given record.
    * If an error is returned, performs exponential backoff; for this reason the whole operation is carried out in a
    * blocking manner inside a Future
    * @param target [[ScanTarget]] to update
    * @param newValue new value to set (boolean)
    * @return a Future which contains the updated [[ScanTarget]], or fails if the operation can't be completed.
    */
  def setInProgress(target:ScanTarget, newValue:Boolean) = Future {
    implicit val client = ddbClientMgr.getNewDynamoClient(config.getOptional[String]("externalData.awsProfile"))
    val updatedRecord = target.copy(scanInProgress = newValue)

    /*
    having a Try in the Future seems pointless, as it's neater to deal with the Failure case with Future code only.
    so, we convert here by failing the future if the retry op failed.
     */
    doNextRetry(updatedRecord, initialRetryDelay, 1) match {
      case Success(scanTarget)=>scanTarget
      case Failure(err)=>throw err
    }
  }

  def setScanCompleted(tgt:ScanTarget, completionTime:Option[ZonedDateTime] = None, error:Option[Throwable] = None) = Future {
    implicit val client = ddbClientMgr.getNewDynamoClient(config.getOptional[String]("externalData.awsProfile"))
    val timestampToUse = completionTime match {
      case Some(time)=>time
      case None=>ZonedDateTime.now()
    }
    val updatedRecord = tgt.copy(scanInProgress = false,lastScanned=Some(timestampToUse),lastError=error.map(_.toString))

    doNextRetry(updatedRecord, initialRetryDelay, 1) match {
      case Success(scanTarget)=>scanTarget
      case Failure(err)=>throw err
    }
  }
}