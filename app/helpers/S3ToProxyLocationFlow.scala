package helpers

import akka.stream.alpakka.s3.scaladsl._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyLocation}
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class S3ToProxyLocationFlow (s3ClientMgr: S3ClientManager, config:Configuration, mainMediaBucket:String, potentialMediaBuckets:Seq[String]) extends GraphStage[FlowShape[ListBucketResultContents, ProxyLocation]] {
  final val in:Inlet[ListBucketResultContents] = Inlet.create("S3ToProxyLocationFlow.in")
  final val out:Outlet[ProxyLocation] = Outlet.create("S3ToProxyLocationFlow.out")

  override def shape: FlowShape[ListBucketResultContents, ProxyLocation] = {
    FlowShape.of(in,out)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      implicit val s3Client:AmazonS3 = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"))
      private val logger=Logger(getClass)

      logger.debug("initialised new instance")
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          logger.debug(s"got element $elem")
          try {
            //we need to do a metadata lookup to get the MIME type anyway, so we may as well just call out here.
            //it appears that you can't push() to a port from in a Future thread, so doing it the crappy way and blocking here.
            throw new RuntimeException("FIXME: no media item key at this point so can't work.")
            val proxyLocationFuture = ProxyLocation.fromS3(elem.bucketName, elem.key, mainMediaBucket, "INVALIDMEDIAITEM")
            val mappedElem = Await.result(proxyLocationFuture, 3.seconds)
            logger.debug(s"Mapped $elem to $mappedElem")

            while (!isAvailable(out)) {
              logger.debug("waiting for output port to be available")
              Thread.sleep(500L)
            }
            push(out, mappedElem)
          } catch {
            case ex:Throwable=>
              logger.error(s"Could not create ProxyLocation: ", ex)
              pull(in)
          }
        }
      })

      setHandler(out, new AbstractOutHandler {
        override def onPull(): Unit = {
          logger.debug("pull from downstream")
          pull(in)
        }
      })
    }

}
