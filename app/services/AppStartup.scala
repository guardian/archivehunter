package services

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import javax.inject.{Inject, Named}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AppStartup @Inject() (@Named("bucketScannerActor") bucketScanner:ActorRef, actorSystem:ActorSystem, config:Configuration)(implicit system:ActorSystem){
  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.dispatcher

  logger.info("Starting up management and cluster bootstrap")

  AkkaManagement(system).start()
  ClusterBootstrap(system).start()
}
