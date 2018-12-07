package services

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import javax.inject.{Inject, Named}
import play.api.inject.{ApplicationLifecycle, Injector}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

class AppStartup @Inject()(@Named("bucketScannerActor") bucketScanner:ActorRef, actorSystem:ActorSystem,
                            config:Configuration, injector:Injector, lifecycle: ApplicationLifecycle)(implicit system:ActorSystem){
  private val logger = Logger(getClass)

  implicit val ec:ExecutionContext = system.dispatcher

  logger.info("Starting up management and cluster bootstrap")

  AkkaManagement(system).start()
  ClusterBootstrap(system).start()

  logger.info("Starting up master timer")

  system.actorOf(ClusterSingletonManager.props(
    singletonProps = Props(injector.instanceOf(classOf[ClockSingleton])),
    terminationMessage = PoisonPill,
    settings = ClusterSingletonManagerSettings(system)
  ), name="ClockSingleton"
  )

  def doTestCreateIndex():Unit = {
    val indexMgt = injector.instanceOf(classOf[IndexManagement])
    indexMgt.verifyIndexStatus().map({
      case Left(err) =>
        logger.error(s"Could not verify index status: $err.")
        if(!err.error.reason.contains("does not exist")){
          lifecycle.stop()
          return
        }
      case Right(result)=>

    })
    indexMgt.doIndexCreate().onComplete({
      case Success(Left(err))=>
        if(err.error.`type`!="resource_already_exists_exception")  logger.error(s"Index create request failed: $err")
      case Success(Right(response))=>
        logger.info(s"Index create successful: $response")
    })
  }
  doTestCreateIndex()
}
