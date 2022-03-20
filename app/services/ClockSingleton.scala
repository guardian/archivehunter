package services

import akka.actor.{Actor, ActorRef, Timers}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters}
import javax.inject.{Inject, Named, Singleton}
import play.api.Logger
import services.BucketScanner.{RegularScanTrigger, TickKey}
import services.ClockSingleton.RapidClockTick
import services.DynamoCapacityActor.TimedStateCheck

import scala.concurrent.duration._

object ClockSingleton {
  trait CSMsg

  case object RapidClockTick
  case object SlowClockTick
  case object VerySlowClockTick
  case object ScanTick
}

/**
  * this actor implements a simple "clock" that sends periodic updates to other actors that need to run timed operations.
  * the idea of isolating the timers in this way is to ensure that there is only one actor in the cluster sending timing pulses,
  * but the actors doing the work can fan-out and run in parallel.
  */
@Singleton
class ClockSingleton @Inject() (@Named("dynamoCapacityActor") dynamoCapacityActor:ActorRef,
                                @Named("bucketScannerActor") bucketScanner:ActorRef,
                                @Named("jobPurgerActor") jobPurgerActor: ActorRef,
                               //FIXME: this feels wrong, think it should be called, needs investigation
                                @Named("glacierRestoreActor") glacierRestoreActor:ActorRef,
                                config:ArchiveHunterConfiguration,
                               ) extends Actor with Timers with ExtValueConverters{
  import ClockSingleton._
  private val logger=Logger(getClass)

  timers.startTimerAtFixedRate(RapidClockTick, RapidClockTick, 30.seconds)
  timers.startTimerAtFixedRate(SlowClockTick, SlowClockTick, 10.minutes)
  timers.startTimerAtFixedRate(VerySlowClockTick, VerySlowClockTick, 1.hours)

  timers.startTimerAtFixedRate(ScanTick, ScanTick, Duration(config.get[Long]("scanner.masterSchedule"),SECONDS))

  override def receive: Receive = {
    case RapidClockTick=>
      logger.debug("ClockSingleton: RapidClockTick")
      dynamoCapacityActor ! DynamoCapacityActor.TimedStateCheck
    case SlowClockTick=>
      logger.debug("ClockSingleton: SlowClockTick")
    case VerySlowClockTick=>
      logger.debug("ClockSingleton: VerySlowClockTick")
      jobPurgerActor ! JobPurgerActor.StartJobPurge
    case ScanTick=>
      logger.debug("ClockSingleton: ScanTick")
      bucketScanner ! BucketScanner.RegularScanTrigger
  }
}
