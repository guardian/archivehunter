package services

import akka.actor.{Actor, ActorRef, Timers}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters}
import javax.inject.{Inject, Named}
import play.api.Logger
import services.BucketScanner.{RegularScanTrigger, TickKey}
import services.ClockSingleton.RapidClockTick
import services.DynamoCapacityActor.TimedStateCheck

import scala.concurrent.duration._

object ClockSingleton {
  trait CSMsg

  case object RapidClockTick
  case object SlowClockTick
  case object ScanTick

}

/**
  * this actor implements a simple "clock" that sends periodic updates to other actors that need to run timed operations.
  * the idea of isolating the timers in this way is to ensure that there is only one actor in the cluster sending timing pulses,
  * but the actors doing the work can fan-out and run in parallel.
  */
class ClockSingleton @Inject() (@Named("dynamoCapacityActor") dynamoCapacityActor:ActorRef,
                                @Named("etsProxyActor") etsProxyActor:ActorRef,
                                @Named("bucketScannerActor") bucketScanner:ActorRef,
                                config:ArchiveHunterConfiguration,
                               ) extends Actor with Timers with ExtValueConverters{
  import ClockSingleton._
  private val logger=Logger(getClass)

  timers.startPeriodicTimer(RapidClockTick, RapidClockTick, 10.seconds)
  timers.startPeriodicTimer(SlowClockTick, SlowClockTick, 1.minutes)
  timers.startPeriodicTimer(ScanTick, ScanTick, Duration(config.get[Long]("scanner.masterSchedule"),SECONDS))

  override def receive: Receive = {
    case RapidClockTick=>
      logger.debug("ClockSingleton: RapidClockTick")
      //dynamoCapacityActor ! DynamoCapacityActor.TimedStateCheck
      //etsProxyActor ! ETSProxyActor.CheckForNotifications
    case SlowClockTick=>
      logger.debug("ClockSingleton: SlowClockTick")
      //etsProxyActor ! ETSProxyActor.CheckPipelinesStatus
    case ScanTick=>
      logger.debug("ClockSingleton: ScanTick")
      bucketScanner ! BucketScanner.RegularScanTrigger
  }
}
