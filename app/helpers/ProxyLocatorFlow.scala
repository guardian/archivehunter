package helpers

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTargetDAO
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation}
import javax.inject.Inject
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

  lazy val defaultRegion = playConfig.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var proxiesToOutput:Seq[ProxyLocation] = Seq()

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        logger.debug(s"Got archive entry $elem")
        implicit val s3Client = s3ClientManager.getS3Client(awsProfile, elem.region)
        val potentialProxyLocationsResult = Await.result(ProxyLocator.findProxyLocation(elem), 10 seconds)

        val potentialProxyLocations = potentialProxyLocationsResult.collect({case Right(thing)=>thing})

        logger.debug(s"Got $potentialProxyLocations for $elem")
        if(potentialProxyLocations.isEmpty){
          logger.info(s"Could not find any potential proxies for ${elem.bucket}/${elem.path}")
          pull(in)
        } else {
          logger.info(s"Found ${potentialProxyLocations.length} potential proxies for ${elem.bucket}/${elem.path}")
          proxiesToOutput = proxiesToOutput ++ potentialProxyLocations
          logger.debug(s"Outputting ${proxiesToOutput.head}")
          push(out, proxiesToOutput.head)
          proxiesToOutput = proxiesToOutput.tail
        }
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(proxiesToOutput.nonEmpty) {
          push(out, proxiesToOutput.head)
          proxiesToOutput = proxiesToOutput.tail
        } else {
          pull(in)
        }
      }
    })
  }
}
