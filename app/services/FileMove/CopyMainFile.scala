package services.FileMove

import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.DocId
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import play.api.Configuration

/**
  * this actor copies a file to the requested destination bucket and updates the internal state with the new file ID.
  * when rolling back, it checks that the source file still exists and if so deletes the one it copied earlier.
  */
class CopyMainFile (s3ClientManager: S3ClientManager, config:Configuration) extends GenericMoveActor with DocId {
  import GenericMoveActor._

  override def receive: Receive = {
    case PerformStep(currentState)=>
      val s3Client = s3ClientManager.getS3Client(region=Some(currentState.destRegion),profileName=config.getOptional[String]("externalData.awsProfile"))
      currentState.entry match {
        case None=>
          sender() ! StepFailed(currentState, "No archive entry source")
        case Some(entry)=>
          try {
            logger.info(s"Copying ${entry.bucket}:${entry.path} to  ${currentState.destBucket}:${entry.path}")
            val updatedState = currentState.copy(destFileId = Some(makeDocId(currentState.destBucket, entry.path)))
            s3Client.copyObject(entry.bucket, entry.path, currentState.destBucket, entry.path)
            logger.info("Copy succeded")
            sender() ! StepSucceeded(updatedState)
          } catch {
            case err:Throwable=>
              logger.error(s"Could not copy $entry", err)
              sender() ! StepFailed(currentState, err.toString)
          }
      }

    case RollbackStep(currentState)=>
      val destClient = s3ClientManager.getS3Client(region=Some(currentState.destRegion),profileName=config.getOptional[String]("externalData.awsProfile"))
      val sourceClient = s3ClientManager.getS3Client(region=currentState.entry.flatMap(_.region),profileName=config.getOptional[String]("externalData.awsProfile"))
      currentState.entry match {
        case None=>
          sender() ! StepFailed(currentState, "No archive entry source")
        case Some(entry)=>
          try {
            logger.info(s"Rolling back failed file move, going to delete ${currentState.destBucket}:${entry.path} if ${entry.bucket}:${entry.path} exists")
            if(!sourceClient.doesObjectExist(entry.bucket, entry.path)){
              sourceClient.copyObject(currentState.destBucket, entry.path, entry.bucket, entry.path)  //raises if the copy-back fails
            }
            destClient.deleteObject(currentState.destBucket,entry.path)
            logger.info(s"Rollback succeeded")
            sender() ! StepSucceeded(currentState.copy(destFileId = None))
          } catch {
            case err:Throwable=>
              logger.error(s"Could not rollback copy for $entry", err)
              sender() ! StepFailed(currentState, err.toString)
          }
      }
  }
}
