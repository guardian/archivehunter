package services

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import javax.inject.{Inject, Named}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AppStartup @Inject() (@Named("bucketScannerActor") bucketScanner:ActorRef, config:Configuration)(implicit system:ActorSystem){
  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.dispatcher

  logger.info("AppStartup")

}
