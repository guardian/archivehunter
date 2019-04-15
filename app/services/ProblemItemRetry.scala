package services

import akka.actor.{Actor, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.{ProblemItemIndexer, ProxyLocationDAO}
import helpers.{CreateProxySink, SearchHitToArchiveEntryFlow}
import javax.inject.Inject
import play.api.{Configuration, Logger}

object ProblemItemRetry {
  case class RetryForCollection(collectionName: String)
}

class ProblemItemRetry @Inject()(config:Configuration, proxyGenerators:ProxyGenerators, esClientManager:ESClientManager)(implicit actorSystem: ActorSystem, proxyLocationDAO:ProxyLocationDAO) extends Actor {
  import ProblemItemRetry._

  private val logger=Logger(getClass)

  implicit val mat:Materializer = ActorMaterializer.create(actorSystem)
  private val problemItemIndexName = config.get[String]("externalData.problemItemsIndex")
  private val problemItemIndexer = new ProblemItemIndexer(problemItemIndexName)

  protected implicit val esClient = esClientManager.getClient()

  override def receive: Receive = {
    case RetryForCollection(collectionName)=>
      logger.info(s"Starting problem item scan for $collectionName")
      problemItemIndexer.sourceForCollection(collectionName)
        .via(new SearchHitToArchiveEntryFlow()).log("ProblemItemRetry").async
        .to(new CreateProxySink(proxyGenerators))
      logger.info(s"Problem item scan underway for $collectionName")
  }
}
