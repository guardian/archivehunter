package services

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import javax.inject.{Inject, Named}
import play.api.{Configuration, Logger}
import services.ScanTimerSingleton.Startup

class AppStartup @Inject() (@Named("bucketScannerActor") bucketScanner:ActorRef, config:Configuration)(implicit system:ActorSystem){
  private val logger = Logger(getClass)

  logger.info("AppStartup")
  /**
    * register a singleton for the master cluster timer. The cluster manager ensures that only one of these is actually running.
    * to access it, use:
    * val proxy = system.actorOf(
    *   ClusterSingletonProxy.props(
    * singletonManagerPath = "/user/scanTimerSingleton",
    * settings = ClusterSingletonProxySettings(system)),
    * name = "scanTimerSingleton")
    *
    * see https://doc.akka.io/docs/akka/2.5/cluster-singleton.html for more details
    *
    */
  val timerSingleton = system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = Props(new ScanTimerSingleton(bucketScanner, system)),
      terminationMessage = ScanTimerSingleton.End,
      settings = ClusterSingletonManagerSettings(system)
    ),
    name = "scanTimerSingleton")

  val proxy = system.actorOf(
    ClusterSingletonProxy.props(
     singletonManagerPath = "/user/scanTimerSingleton",
     settings = ClusterSingletonProxySettings(system)),
   name = "scanTimerSingletonProxy")
  proxy ! Startup
}