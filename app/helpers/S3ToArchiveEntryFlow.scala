package helpers

import java.time.{ZoneId, ZonedDateTime}

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.alpakka.s3.scaladsl._
import akka.stream.scaladsl._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import javax.inject.Inject
import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class S3ToArchiveEntryFlow @Inject() (s3ClientMgr: S3ClientManager) extends GraphStage[FlowShape[ListBucketResultContents, ArchiveEntry]] {
  final val in:Inlet[ListBucketResultContents] = Inlet.create("S3ToArchiveEntry.in")
  final val out:Outlet[ArchiveEntry] = Outlet.create("S3ToArchiveEntry.out")

  override def shape: FlowShape[ListBucketResultContents, ArchiveEntry] = {
    FlowShape.of(in,out)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      implicit val s3Client:AmazonS3 = s3ClientMgr.getS3Client()


      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          val logger=Logger(this.getClass)

          //we need to do a metadata lookup to get the MIME type anyway, so we may as well just call out here.
          ArchiveEntry.fromS3(elem.bucketName, elem.key).map(entry=>{
            push(out, entry)
            pull(in)
          }).recoverWith({
            case err:Throwable=>
              logger.error("Could not convert S3 listing entry to ArchiveEntry", err)
              pull(in)
              Future()
          })
//          ArchiveEntry(ArchiveEntry.makeDocId(elem.bucketName, elem.key),elem.bucketName,elem.key,ArchiveEntry.getFileExtension(elem.key),
//            elem.size,ZonedDateTime.ofInstant(elem.lastModified, ZoneId.systemDefault()),elem.eTag,MimeType)


        }
      })

      setHandler(out, new AbstractOutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }

}
