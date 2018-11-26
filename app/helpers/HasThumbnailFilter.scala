package helpers

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveHunterConfiguration, ProxyLocationDAO, ProxyType}
import javax.inject.Inject
import play.api.Logger

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * Akka Flow stage that filters out anything that already has a thumbnail
  * @param proxyLocationDAO injected [[ProxyLocationDAO]] instance
  * @param ddbClientManager injected [[DynamoClientManager]] instance
  * @param config injected [[ArchiveHunterConfiguration]] instance
  */
class HasThumbnailFilter @Inject() (proxyLocationDAO: ProxyLocationDAO,ddbClientManager:DynamoClientManager, config:ArchiveHunterConfiguration) extends GraphStage[FlowShape[ArchiveEntry,ArchiveEntry]]{
  private final val in:Inlet[ArchiveEntry] = Inlet.create("HasProxyFilter.in")
  private final val out:Outlet[ArchiveEntry] = Outlet.create("HasProxyFilter.out")
  private val logger = Logger(getClass)

  override def shape: FlowShape[ArchiveEntry, ArchiveEntry] = FlowShape.of(in,out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val awsProfile = config.getOptional("externalData.awsProfile").map(_.mkString)
    implicit val ddbClient = ddbClientManager.getClient(awsProfile)

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        try {
          Await.result(proxyLocationDAO.getProxy(elem.id, ProxyType.THUMBNAIL), 10 seconds) match {
            case None=>
              logger.debug(s"No thumbnail available for ${elem.id} - passing")
              push(out, elem)
            case Some(location)=>
              logger.debug(s"Proxy location for ${elem.id}: s3://${location.bucketName}/${location.bucketPath} - dropping")
              pull(in)
          }
        } catch {
          case ex:Throwable=>
            logger.error("Could not look up proxy:", ex)
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
