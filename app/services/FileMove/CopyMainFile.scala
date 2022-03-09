package services.FileMove

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.s3.MultipartUploadResult
import com.amazonaws.regions.Regions
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
class CopyMainFile (s3ClientManager: S3ClientManager, config:Configuration, largeFileCopier:ImprovedLargeFileCopier)
                   (implicit val actorSystem: ActorSystem, mat:Materializer) extends GenericMoveActor with DocId {
  import GenericMoveActor._

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
      ImprovedLargeFileCopier.CompletedUpload(s"s3://${destBucket}/$path", destBucket, path, result.getETag, None, None, None, None)
    })

  val maybeProfile = config.getOptional[String]("externalData.awsProfile")

  override def receive: Receive = {
    case PerformStep(currentState)=>
      implicit val s3Client = s3ClientManager.getS3Client(region=Some(currentState.destRegion),profileName=maybeProfile)
      currentState.entry match {
        case None=>
          sender() ! StepFailed(currentState, "No archive entry source")
        case Some(entry)=>
          val originalSender = sender()

          val copyFuture = if(entry.size<5368709120L) {  //5gb and larger files can't be directly copied and must be re-uploaded
            standardS3Copy(currentState.destBucket, entry.bucket, entry.path)
          } else {
            val rgn = entry.region.map(regionString=>Regions.valueOf(regionString.toUpperCase)).getOrElse(Regions.EU_WEST_1)
            largeFileCopier.performCopy(rgn,
              Some(s3ClientManager.newCredentialsProvider(maybeProfile)),
              entry.bucket, entry.path, None, currentState.destBucket, entry.path)
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
              val rgn = entry.region.map(regionString=>Regions.valueOf(regionString.toUpperCase)).getOrElse(Regions.EU_WEST_1)
              largeFileCopier.performCopy(rgn,
                Some(s3ClientManager.newCredentialsProvider(maybeProfile)),
                entry.bucket, entry.path, None, currentState.destBucket, entry.path)
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
