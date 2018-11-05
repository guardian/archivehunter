package helpers

import java.time.{ZoneId, ZonedDateTime}

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.alpakka.s3.scaladsl._
import akka.stream.scaladsl._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import scala.concurrent.{Await, Future}

class S3ToArchiveEntryFlow @Inject() (s3ClientMgr: S3ClientManager, config:Configuration) extends GraphStage[FlowShape[ListBucketResultContents, ArchiveEntry]] {
  final val in:Inlet[ListBucketResultContents] = Inlet.create("S3ToArchiveEntry.in")
  final val out:Outlet[ArchiveEntry] = Outlet.create("S3ToArchiveEntry.out")

  override def shape: FlowShape[ListBucketResultContents, ArchiveEntry] = {
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

          //we need to do a metadata lookup to get the MIME type anyway, so we may as well just call out here.
          logger.debug(s"got element $elem")
//          ArchiveEntry.fromS3(elem.bucketName, elem.key).map(entry=>{
//            logger.debug(s"Mapped $elem to $entry")
//            push(out, entry)  //the downstream then pulls us for next record
//          }).recoverWith({
//            case err:Throwable=>
//              logger.error("Could not convert S3 listing entry to ArchiveEntry", err)
//              pull(in)
//              Future()
//          })
          val mappedElem = Await.result(ArchiveEntry.fromS3(elem.bucketName, elem.key), 3.seconds)
          logger.debug(s"Mapped $elem to $mappedElem")

          while(!isAvailable(out)){
            logger.debug("waiting for output port to be available")
            Thread.sleep(500L)
          }
          push(out, mappedElem)

//          ArchiveEntry(ArchiveEntry.makeDocId(elem.bucketName, elem.key),elem.bucketName,elem.key,ArchiveEntry.getFileExtension(elem.key),
//            elem.size,ZonedDateTime.ofInstant(elem.lastModified, ZoneId.systemDefault()),elem.eTag,MimeType)

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
