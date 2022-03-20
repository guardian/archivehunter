package services

import akka.actor.{Actor, ActorRef, Timers}
import com.theguardian.multimedia.archivehunter.common.ExtValueConverters
import play.api.Logger

import javax.inject.Named
import scala.concurrent.duration._

class ClockPerInstance(
                        @Named("ingestProxyQueue") ingestProxyQueue: ActorRef,
                        @Named("proxyFrameworkQueue") proxyFrameworkQueue:ActorRef,
                        @Named("fileMoveQueue") fileMoveQueue:ActorRef,
                      ) extends Actor with Timers with ExtValueConverters {
  import ClockSingleton._

  timers.startTimerAtFixedRate(RapidClockTick, RapidClockTick, 30.seconds)

  override def receive: Receive = {
    case RapidClockTick=>
      //these message queues don't need to be queried by a singleton so we can "fan out" the workload here
      ingestProxyQueue ! GenericSqsActor.CheckForNotifications
      proxyFrameworkQueue ! GenericSqsActor.CheckForNotifications
      fileMoveQueue ! FileMoveQueue.CheckForNotificationsIfIdle
  }
}
