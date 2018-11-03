package services

import java.time.ZonedDateTime

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable}
import javax.inject.{Inject, Named}
import play.api.Logger

import scala.concurrent.duration._

object ScanTimerSingleton {
  case class End()  //sent when shutting down
  case class Startup()  //sent when starting up
}

/**
  * singleton actor whose responsibility is to set up regular scans via the actorsystem
  * @param bucketScanner named injection of ActorRef for the bucket scanner
  * @param system injected ActorSystem reference
  */
class ScanTimerSingleton @Inject() (@Named("bucketScannerActor") bucketScanner:ActorRef, system:ActorSystem) extends Actor {
  import ScanTimerSingleton._
  private val logger = Logger(getClass)

  implicit val ec=system.dispatcher
  var amStarted=false
  var cancellable:Option[Cancellable] = None
  var lastRunAt:Option[ZonedDateTime] = None

  override def receive: Receive = {
    case End=>
      logger.warn("Timer singleton is shutting down")
      if(cancellable.isDefined) cancellable.get.cancel()
    case Startup=>
      if(!amStarted) {
        logger.info("Starting up regular scan timer")
        amStarted = true
        cancellable = Some(system.scheduler.schedule(5.seconds, 5.minutes, bucketScanner, BucketScanner.RegularScanTrigger))
      } else {
        logger.info("System already running, not starting regular scan timer")
      }
  }
}
