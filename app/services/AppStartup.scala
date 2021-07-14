package services

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.management.scaladsl.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import javax.inject.{Inject, Named}
import play.api.inject.{ApplicationLifecycle, Injector}
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class AppStartup @Inject()(injector:Injector)(implicit system:ActorSystem){
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

    indexMgt.doIndexCreate().onComplete({
      case Success(response)=>
        if(response.isError && response.error.`type`!="resource_already_exists_exception")  {
          logger.error(s"Index create request failed: ${response.status} ${response.error.reason}")
        } else {
          logger.info(s"Index create successful: $response")
        }
      case Failure(err)=>
        logger.error(s"Index create request failed: $err")
    })
  }
  doTestCreateIndex()
}
