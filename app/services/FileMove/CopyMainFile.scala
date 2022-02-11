package services.FileMove

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes, Uri}
import akka.stream.Materializer
import akka.stream.alpakka.s3.MultipartUploadResult
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Keep, Sink}
import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.DocId
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import play.api.Configuration

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * this actor copies a file to the requested destination bucket and updates the internal state with the new file ID.
  * when rolling back, it checks that the source file still exists and if so deletes the one it copied earlier.
  */
class CopyMainFile (s3ClientManager: S3ClientManager, config:Configuration) extends GenericMoveActor with DocId {
  import GenericMoveActor._
  //create a separate materializer for this stage, to keep the copy-operation separate from the main server
  val temporaryActorSystem = ActorSystem.create("CopyMainFile")
  implicit val mat:Materializer = Materializer.createMaterializer(temporaryActorSystem)

  override def postStop(): Unit = {
    temporaryActorSystem.terminate().onComplete({
      case Success(_)=>
        logger.info(s"CopyMainFile sub actor system has been correctly terminated")
      case Failure(err)=>
        logger.warn(s"Could not terminate CopyMainFile sub actor system: ${err.getMessage}", err)
    })
  }
  /**
    * Request a standard S3 bucket->bucket copy. This only works on files less than 5Gb in size; for larger ones you
    * need to download and re-upload - this is done via streaming in `largeFileCopy`. In order to maintain compatibility
    * between these two implementations, the return value is a `MultipartUploadResult` even though multi-part is not used here.
    *
    * @param destBucket bucket to copy into
    * @param sourceBucket bucket to copy from
    * @param path path of the file to copy
    * @param s3Client implicitly provided S3 client object
    * @return a Future, containing a MultipartUploadResult which fails on error.  This method is, however, synchronous under the hood until
    *         updated to AWS SDK v2
    */
  def standardS3Copy(destBucket:String, sourceBucket:String, path:String)(implicit s3Client:AmazonS3) =
    Future.fromTry(Try {
      logger.info(s"Copying ${sourceBucket}:${path} to  ${destBucket}:${path}")

      val result = s3Client.copyObject(sourceBucket, path, destBucket, path)
      logger.info("Copy succeeded")
      MultipartUploadResult(Uri(), destBucket, path, result.getETag, Option(result.getVersionId))
    })

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

  override def receive: Receive = {
    case PerformStep(currentState)=>
      implicit val s3Client = s3ClientManager.getS3Client(region=Some(currentState.destRegion),profileName=config.getOptional[String]("externalData.awsProfile"))
      currentState.entry match {
        case None=>
          sender() ! StepFailed(currentState, "No archive entry source")
        case Some(entry)=>
          val originalSender = sender()

          val copyFuture = if(entry.size<5368709120L) {  //5gb and larger files can't be directly copied and must be re-uploaded
            standardS3Copy(currentState.destBucket, entry.bucket, entry.path)
          } else {
            largeFileCopy(currentState.destBucket, entry.bucket, entry.path, entry.size)
          }

          copyFuture.onComplete({
            case Success(result)=>
              logger.info(s"Successfully copied ${entry.path} to s3://${result.bucket}/${result.key}")
              val updatedState = currentState.copy(destFileId = Some(makeDocId(currentState.destBucket, entry.path)))
              originalSender ! StepSucceeded(updatedState)
            case Failure(err)=>
              logger.error(s"Could not copy s3://${entry.bucket}/${entry.path} to s3://${currentState.destBucket}/${entry.path}: ${err.getMessage}", err)
              originalSender ! StepFailed(currentState, err.getMessage)
          })
      }

    case RollbackStep(currentState)=>
      val destClient = s3ClientManager.getS3Client(region=Some(currentState.destRegion),profileName=config.getOptional[String]("externalData.awsProfile"))
      val sourceClient = s3ClientManager.getS3Client(region=currentState.entry.flatMap(_.region),profileName=config.getOptional[String]("externalData.awsProfile"))
      currentState.entry match {
        case None=>
          sender() ! StepFailed(currentState, "No archive entry source")
        case Some(entry)=>
          val originalSender = sender()

          logger.info(s"Rolling back failed file move, going to delete ${currentState.destBucket}:${entry.path} if ${entry.bucket}:${entry.path} exists")

          val copyBackFuture = if(!sourceClient.doesObjectExist(entry.bucket, entry.path)){
            //if the file no longer exists in the source bucket, then copy it back from the destination
            if(entry.size<5368709120L) {
              logger.info(s"File no longer exists on s3://${entry.bucket}/${entry.path}, copying it back with standard copy...")
              standardS3Copy(entry.bucket, currentState.destBucket, entry.path)(destClient).map(result=>Some(result))
            } else {
              logger.info(s"File no longer exists on s3://${entry.bucket}/${entry.path}, copying it back with large-file copy...")
              largeFileCopy(entry.bucket, currentState.destBucket, entry.path, entry.size).map(result=>Some(result))
            }
          } else {
            logger.info(s"File already exists on s3://${entry.bucket}/${entry.path}, no copy-back required")
            Future(None)
          }

          val resultFut = for {
            _ <- copyBackFuture
            deleteResult <- Future.fromTry(Try { destClient.deleteObject(currentState.destBucket, entry.path) })
          } yield deleteResult

          resultFut.onComplete({
            case Success(_)=>
              originalSender ! StepSucceeded(currentState.copy(destFileId = None))
            case Failure(err)=>
              logger.error(s"Could not rollback copy for $entry: ${err.getMessage}", err)
              originalSender ! StepFailed(currentState, err.toString)
          })
//          try {
//
//            if(!sourceClient.doesObjectExist(entry.bucket, entry.path)){
//              sourceClient.copyObject(currentState.destBucket, entry.path, entry.bucket, entry.path)  //raises if the copy-back fails
//            }
//            destClient.deleteObject(currentState.destBucket,entry.path)
//            logger.info(s"Rollback succeeded")
//            sender() ! StepSucceeded(currentState.copy(destFileId = None))
//          } catch {
//            case err:Throwable=>
//              logger.error(s"Could not rollback copy for $entry", err)
//              sender() ! StepFailed(currentState, err.toString)
//          }
      }
  }
}
