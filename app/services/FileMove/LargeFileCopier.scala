package services.FileMove

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import play.api.Configuration

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object LargeFileCopier {
  private val logger = LoggerFactory.getLogger(getClass)
  //create a separate materializer for this stage, to keep the copy-operation separate from the main server
  val temporaryActorSystem = ActorSystem.create("CopyMainFile", ConfigFactory.empty())
  implicit val mat:Materializer = Materializer.createMaterializer(temporaryActorSystem)

  val defaultPartSize:Int = 50*1024*1024  //default chunk size is 50Mb

  /**
    * AWS specifications say that parts must be at least 5Mb in size but no more than 5Gb in size, and that there
    * must be a maximum of 10,000 parts for an upload.  This makes the effective file size limit 5Tb
    * @param totalFileSize actual size of the file to upload, in bytes
    * @return the target path size
    */
  def estimatePartSize(totalFileSize:Long):Int = {
    val maxWantedParts = 500

    var partSize:Int = defaultPartSize
    var nParts:Int = maxWantedParts + 1
    var i:Int=1
    while(true) {
      nParts = (totalFileSize / partSize).toInt
      if (nParts > maxWantedParts) {
        i = i+1
        partSize = defaultPartSize*i
      } else {
        logger.info(s"Part size estimated at $partSize for $nParts target parts")
        return partSize
      }
    }
    defaultPartSize
  }

  /**
    * Copy a file from one bucket to another by streaming the contents through the application.  This is the only option
    * to copy files larger than 5Gb.
    *
    * @param destBucket bucket to copy into
    * @param sourceBucket bucket to copy from
    * @param path path of the file to copy
    * @return a Future containing a MultipartUploadResult which fails on error.
    */
  def largeFileCopy(destBucket:String, sourceBucket:String, path:String, fileSize:Long) = {
    logger.info(s"Setting up large-file copy for s3://$sourceBucket/$path into $destBucket")
    val s3file = S3.download(sourceBucket, path)
    s3file.runWith(Sink.head).flatMap({ //the download method materializes once when the file is found, that passes us another source for streming the data.
      case None=>
        logger.error(s"Could not find large S3 file s3://$sourceBucket/$path")
        Future.failed(new RuntimeException(s"File does not exist: s3://$sourceBucket/$path"))
      case Some((src, metadata))=>
        val ct = metadata.contentType match {
          case Some(providedContentType)=>
            ContentType.parse(providedContentType) match {
              case Left(errs)=>
                logger.error(s"S3-provided content type $providedContentType for s3://$sourceBucket/$path was not valid: $errs")
                ContentTypes.`application/octet-stream`
              case Right(ct)=>ct
            }
          case None=>
            logger.warn(s"s3://$sourceBucket/$path has no provided content-type, defaulting to application/octet-stream")
            ContentTypes.`application/octet-stream`
        }
        logger.info(s"Performing large-file copy for s3://$sourceBucket/$path to s3://$destBucket/$path. Content type is $ct")
        val sink = S3.multipartUpload(destBucket, path, contentType = ct, chunkingParallelism = 1, chunkSize = estimatePartSize(fileSize))
        src.runWith(sink)
    })
  }
}
