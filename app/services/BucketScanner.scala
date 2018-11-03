package services

import java.time.ZonedDateTime
import java.time.temporal.{ChronoUnit, IsoFields, TemporalUnit}

import akka.actor.{Actor, ActorSystem, Timers}
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo.{ScanamoAlpakka, Table}
import helpers.{DynamoClientManager, S3ClientManager, ZonedTimeFormat}
import javax.inject.Inject
import models.ScanTarget
import play.api.{Configuration, Logger}
import services.BucketScanner.{PerformTargetScan, RegularScanTrigger, ScanTargetsUpdated}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object BucketScanner {
  trait BSMsg

  case object TickKey

  case class ScanTargetsUpdated() extends BSMsg
  case class PerformTargetScan(record:ScanTarget) extends BSMsg
  case object RegularScanTrigger extends BSMsg
}


class BucketScanner @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager, s3ClientMgr:S3ClientManager)(implicit system:ActorSystem)
  extends Actor with ZonedTimeFormat with Timers{
  import BucketScanner._

  private val logger=Logger(getClass)

  implicit val mat = ActorMaterializer.create(system)
  implicit val ec:ExecutionContext = system.dispatcher

  val table = Table[ScanTarget](config.get[String]("externalData.scanTargets"))

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
        self ! PerformTargetScan(scanTarget)
        true
      } else {
        false
      }
    }
  }

  def doScan(target: ScanTarget) = {

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
      doScan(tgt)
  }
}
