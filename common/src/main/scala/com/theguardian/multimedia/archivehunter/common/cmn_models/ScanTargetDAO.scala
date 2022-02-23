package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import org.scanamo.{DynamoReadError, Scanamo, ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters, ZonedDateTimeEncoder}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math._
import io.circe.generic.auto._
import io.circe.syntax._
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import org.apache.logging.log4j.LogManager
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

@Singleton
class ScanTargetDAO @Inject()(config:ArchiveHunterConfiguration, ddbClientMgr: DynamoClientManager)(implicit actorSystem:ActorSystem, mat:Materializer)
  extends ZonedDateTimeEncoder with ZonedTimeFormat with JobModelEncoder with ExtValueConverters {
  private val logger = LogManager.getLogger(getClass)

  private val cacheTime = 60 //in seconds

  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))
  val maxRetries = config.getOptional[Int]("externalData.maxRetries").getOrElse(10)
  val initialRetryDelay = config.getOptional[Int]("externalData.initialRetryDelay").getOrElse(2)
  val retryDelayFactor = config.getOptional[Double]("externalData.retryDelayFactor").getOrElse(1.5)

  val scanamoAlpakka = ScanamoAlpakka(ddbClientMgr.getNewAsyncDynamoClient(config.getOptional[String]("externalData.awsProfile")))
  implicit val ddbClient : DynamoDbClient = ddbClientMgr.getNewDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  val scanamo = Scanamo(ddbClient)

  private var cachedLookupTable:Map[String, (ScanTarget, ZonedDateTime)] = Map()

  protected def lookupCachedScanTarget(name:String) = this.synchronized {
    cachedLookupTable.get(name).flatMap(entry=>{
      val storedAt = entry._2
      if(storedAt.plusSeconds(cacheTime.toLong).isBefore(ZonedDateTime.now())){ //this entry has expired, remove from the cache
        cachedLookupTable = cachedLookupTable - name
        None
      } else {
        Some(entry._1)
      }
    })
  }

  protected def addToCache(name:String, target:ScanTarget) = this.synchronized {
    cachedLookupTable = cachedLookupTable + (name->(target,ZonedDateTime.now()))
  }

  /**
    * synchronously attempts to make the update, doing exponential delay via sleep until successful.
    * this should be acceptable, since the most likely cause of the operation failing is the DB table running out of provisioned
    * capacity and therefore slowing the app until it's scaled is a good option
    * @param delayTime time to delay this iteration by, if the operation fails
    * @param attempt attempt that we are making
    * @return a Try, with Failure if we have attemped [[maxRetries]] or a [[ScanTarget]] if successful
    */
  private def doNextRetry(updatedRecord:ScanTarget, delayTime:Double, attempt:Int):Try[ScanTarget] = {
    Try { scanamo.exec(table.put(updatedRecord)) } match {
      case Failure(error)=>
        logger.warn(s"Could not update record on attempt $attempt: ${error.toString}")
        if(attempt<maxRetries){
          Thread.sleep((delayTime*1000.0).toLong)
          doNextRetry(updatedRecord, pow(initialRetryDelay.toDouble,retryDelayFactor*attempt), attempt+1)
        } else {
          logger.error(s"Still can't update record after $attempt attempts, bailing")
          Failure(new RuntimeException(error.toString))
        }
      case Success(_)=>Success(updatedRecord)
    }
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

  def put(tgt:ScanTarget) = doNextRetry(tgt,1,1)

  /**
    * gets the [[ScanTarget]] record for the given bucket name
    * @param bucketName bucket name to search for
    * @return a Future, which contains None if no record was found, Left(DynamoReadError) if an error occurred or Right(ScanTarget) if a record was found.
    */
  def targetForBucket(bucketName:String):Future[Option[Either[DynamoReadError,ScanTarget]]] = {
    scanamoAlpakka.exec(
      table.get("bucketName"===bucketName)
    ).runWith(Sink.head)
  }

  /**
    * gets the [[ScanTarget]] record for that is waiting for the given job ID
    * @param jobId
    */
  def waitingForJobId(jobId:String):Future[Either[List[DynamoReadError],Option[ScanTarget]]] = {
    allScanTargets().map(results=>{
      val failures = results.collect({case Left(err)=>err})
      if(failures.nonEmpty){
        Left(failures)
      } else {
        val finalResults = results.collect({case Right(tgt)=>tgt}).filter(tgt=>tgt.pendingJobIds.isDefined && tgt.pendingJobIds.get.contains(jobId))
        Right(finalResults.headOption)
      }
    })
  }

  def allScanTargets():Future[List[Either[DynamoReadError,ScanTarget]]] = {
    val sink = Sink.fold[List[Either[DynamoReadError, ScanTarget]], List[Either[DynamoReadError, ScanTarget]]](List())(_ ++ _)
    scanamoAlpakka.exec(table.scan()).runWith(sink)
  }

  def get(scanTargetName:String) = {
    lookupCachedScanTarget(scanTargetName) match {
      case Some(scanTarget) => Future(Some(Right(scanTarget)))
      case None =>
        scanamoAlpakka
          .exec(table.get("bucketName" === scanTargetName))
          .runWith(Sink.head)
          .map(_.map(_.map(scanTarget=>{
            addToCache(scanTargetName, scanTarget)
            scanTarget
          })))
    }
  }

  def withScanTarget[T](scanTargetName:String)(block:ScanTarget=>T):Future[Option[Either[DynamoReadError,T]]] = {
    get(scanTargetName).map(_.map(_.map(tgt=>block(tgt))))
  }
}
