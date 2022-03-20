package services.FileMove

import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import helpers.DigestSink
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import play.api.{Configuration, Logger}
import services.FileMove.GenericMoveActor.{PerformStep, RollbackStep, StepFailed, StepSucceeded}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class VerifyChecksum (s3ClientManager:S3ClientManager, config:Configuration)(implicit mat:Materializer, ec:ExecutionContext) extends GenericMoveActor {
  override protected val logger = Logger(getClass)

  /**
    * Streams the given file from S3 and performs an MD5 checksum on it.
    * If successful, returns a tuple containing the checksum in a hex-formatted string
    * and the object's metadata
    * @param bucket bucket containing the object
    * @param path path to the object
    * @return a Future containing the object's checksum and metadata
    */
  def getChecksum(bucket:String, path:String) = {
    S3.download(bucket, path)
      .runWith(Sink.head)
      .flatMap({
        case None=>
          logger.error(s"Can't checksum because s3://$bucket/$path does not exist")
          Future.failed(new RuntimeException(s"s3://$bucket/$path does not exist"))
        case Some((source, meta))=>
          source
            .runWith(DigestSink("MD5"))
            .map(checksum=>(Hex.encodeHexString(checksum.toByteBuffer), meta))
      })
  }

  override def receive: Receive = {
    case PerformStep(state)=>
      if(state.entry.isEmpty || state.destFileId.isEmpty) {
        logger.error(s"Either the source or destination file specifiers are empty so I can't verify the checksum")
        sender() ! StepFailed(state, "Either source or destination specifier was empty")
      } else {
        val entry = state.entry.get
        logger.info(s"Verifying checksums for s3://${entry.bucket}/${entry.path} -> s3://${state.destBucket}/${entry.path}")
        val originalSender = sender()
        Future.sequence(Seq(
          getChecksum(entry.bucket, entry.path),
          getChecksum(state.destBucket, entry.path)
        )).onComplete({
          case Success(results)=>
            val sourceCS = results.head._1
            val sourceMeta = results.head._2
            val destCS = results(1)._1
            val destMeta = results(1)._2
            if(destMeta.contentLength != sourceMeta.contentLength) {
              logger.error(s"INVALID COPY s3://${entry.bucket}/${entry.path} has ${sourceMeta.contentLength} bytes and s3://${state.destBucket}/${entry.path} has ${destMeta.contentLength} bytes")
              originalSender ! StepFailed(state, "Content length did not match")
            } else if(sourceCS != destCS){
              logger.error(s"INVALID COPY s3://${entry.bucket}/${entry.path} has a checksum of $sourceCS and s3://${state.destBucket}/${entry.path} has $destCS")
              originalSender ! StepFailed(state, "Checksums did not match")
            } else {
              originalSender ! StepSucceeded(state)
            }
          case Failure(err)=>
            logger.error(s"Could not perform checksum validation for s3://${entry.bucket}/${entry.path} -> s3://${state.destBucket}/${entry.path}: ${err.getMessage}", err)
            originalSender ! StepFailed(state, err.getMessage)
        })
      }
    case RollbackStep(state)=>
      //nothing to roll back here
      logger.info("VerifyChecksum has nothing to roll back")
      sender() ! StepSucceeded(state)
  }
}
