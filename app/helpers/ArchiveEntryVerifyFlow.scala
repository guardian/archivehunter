package helpers

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.alpakka.s3.scaladsl.ListBucketResultContents
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import javax.inject.Inject
import play.api.{Configuration, Logger}

/**
  * this is a Flow component for an Akka stream.
  * it accepts a stream of [[ArchiveEntry]] instances, each one will be verified for existence (but NOT changes) in S3.
  * if it is still present, it will be "swallowed" and the stream will move on
  * if it is NOT still present, the downstream will be passed a new [[ArchiveEntry]] instance with the beenDeleted flag set to TRUE.
  * @param s3ClientMgr injected [[S3ClientManager]] instance
  * @param config injected webapp configuration instance
  */
class ArchiveEntryVerifyFlow @Inject() (s3ClientMgr: S3ClientManager, config:Configuration) extends GraphStage[FlowShape[ArchiveEntry, ArchiveEntry]]{
  final val in:Inlet[ArchiveEntry] = Inlet.create("ArchiveEntryVerifyFlow.in")
  final val out:Outlet[ArchiveEntry] = Outlet.create("ArchiveEntryVerifyFlow.out")

  override def shape: FlowShape[ArchiveEntry, ArchiveEntry] = {
    FlowShape.of(in,out)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      implicit val s3Client: AmazonS3 = s3ClientMgr.getS3Client(config.getOptional[String]("externalData.awsProfile"))
      private val logger = Logger(getClass)

      logger.debug("initialised new instance")
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          if(s3Client.doesObjectExist(elem.bucket, elem.path)){
            logger.debug(s"Object s3://${elem.bucket}/${elem.path} still exists, not passing.")
            pull(in)
          } else {
            logger.debug(s"Object s3://${elem.bucket}/${elem.path} does not exist - flagging as missing and passing on")
            push(out, elem.copy(beenDeleted = true))
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
