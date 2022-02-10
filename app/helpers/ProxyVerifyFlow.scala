package helpers

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocationDAO, ProxyType}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTargetDAO
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * checks the dynamodb table for ANY type of proxy for the given input.
  * If found, then emit an updated index record with the "proxied" field set.  The assumption is that this is being sourced
  * from a search for "proxied" as false
  * @param config
  * @param ddbClientMgr
  * @param proxyLocationDAO
  */
class ProxyVerifyFlow @Inject()(config:Configuration, ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem, proxyLocationDAO: ProxyLocationDAO, mat:Materializer) extends GraphStage[FlowShape[ArchiveEntry,ArchiveEntry]]{
  private val in:Inlet[ArchiveEntry] = Inlet.create("ProxyVerifyFlow.in")
  private val out:Outlet[ArchiveEntry] = Outlet.create("ProxyVerifyFlow.in")

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  val logger = Logger(getClass)

  private implicit val ec:ExecutionContext = system.dispatcher

  override def shape: FlowShape[ArchiveEntry, ArchiveEntry] = FlowShape.of(in,out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private implicit val ddbClient = ddbClientMgr.getNewAsyncDynamoClient(awsProfile)

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        val locationFuture = Future.sequence(Seq(
          proxyLocationDAO.getProxy(elem.id, ProxyType.VIDEO),
          proxyLocationDAO.getProxy(elem.id, ProxyType.AUDIO),
          proxyLocationDAO.getProxy(elem.id, ProxyType.THUMBNAIL),
          proxyLocationDAO.getProxy(elem.id, ProxyType.UNKNOWN)
        ))
        val result = Await.result(locationFuture, 10 seconds).collect({case Some(loc)=>loc})
        if(result.nonEmpty){
          logger.info(s"Found proxies for $elem: ")
          result.foreach(loc=>logger.info(s"\t$loc"))
          logger.info("Outputting record update for proxied=true")
          val updatedRecord = elem.copy(proxied = true)
          push(out, updatedRecord)
        } else {
          logger.info(s"No proxies found for $elem.")
          pull(in)
        }
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}
