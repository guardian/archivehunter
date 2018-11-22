package helpers

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation}
import javax.inject.Inject
import models.ScanTargetDAO
import play.api.{Configuration, Logger}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ProxyLocatorFlow @Inject() (playConfig:Configuration, s3ClientManager: S3ClientManager)(implicit scanTargetDAO:ScanTargetDAO) extends GraphStage[FlowShape[ArchiveEntry, ProxyLocation]]{
  private final val in:Inlet[ArchiveEntry] = Inlet.create("ProxyLocatorFlow.in")
  private final val out:Outlet[ProxyLocation] = Outlet.create("ProxyLocatorFlow.out")
  private val logger = Logger(getClass)

  override def shape: FlowShape[ArchiveEntry, ProxyLocation] = FlowShape.of(in,out)

  val awsProfile = playConfig.getOptional[String]("externalData.awsProfile")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    private implicit val s3Client = s3ClientManager.getS3Client(awsProfile)
    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        logger.debug(s"Got archive entry $elem")

        val potentialProxyLocations = Await.result(ProxyLocator.findProxyLocation(elem), 10 seconds)
        logger.debug(s"Got $potentialProxyLocations for $elem")
        if(potentialProxyLocations.isEmpty){
          logger.info(s"Could not find any potential proxies for ${elem.bucket}/${elem.path}")
          pull(in)
        } else {
          if(potentialProxyLocations.length>1) logger.info(s"Found ${potentialProxyLocations.length} potential proxies for ${elem.bucket}/${elem.path}, going with the first")
          logger.debug(s"Outputting ${potentialProxyLocations.head}")
          push(out, potentialProxyLocations.head)
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
