package services.FileMove

import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.{DocId, ProxyLocation}
import play.api.Configuration

import scala.util.Try

/**
  * this actor copies a file to the requested destination bucket and updates the internal state with the new file ID.
  * when rolling back, it checks that the source file still exists and if so deletes the one it copied earlier.
  */
class CopyProxyFiles (s3ClientManager:S3ClientManager, config:Configuration) extends GenericMoveActor with DocId {
  import GenericMoveActor._

  def deleteCopiedProxies(currentState:FileMoveTransientData, proxyList:Seq[ProxyLocation])(implicit s3Client:AmazonS3) =
    proxyList.foreach(loc=>
      if(s3Client.doesObjectExist(currentState.destProxyBucket, loc.bucketPath)){
        logger.info(s"Deleting copied proxy at ${currentState.destProxyBucket}:${loc.bucketPath}")
        try {
          s3Client.deleteObject(currentState.destProxyBucket, loc.bucketPath)
        } catch {
          case deleteErr:Throwable=>
            logger.error("Could not delete copied proxy: ", deleteErr)
        }
      }
    )

  override def receive: Receive = {
    case PerformStep(currentState)=>
      implicit val s3Client = s3ClientManager.getS3Client(region=Some(currentState.destRegion),profileName=config.getOptional[String]("externalData.awsProfile"))
      currentState.sourceFileProxies match {
        case Some(proxyList) =>
          try {
            val updatedProxyList = proxyList.map(loc=>
              loc.copy(fileId=currentState.destFileId.get,
                proxyId=makeDocId(currentState.destProxyBucket, loc.bucketPath),
                bucketName = currentState.destProxyBucket)
            )

            proxyList.map(proxy => {
              logger.debug(s"Copying from ${proxy.bucketName}:${proxy.bucketPath} to ${currentState.destProxyBucket}:${proxy.bucketPath}")
              s3Client.copyObject(proxy.bucketName, proxy.bucketPath, currentState.destProxyBucket, proxy.bucketPath)
            })

            sender() ! StepSucceeded(currentState.copy(destFileProxy = Some(updatedProxyList)))
          } catch {
            case err:Throwable=>
              logger.error(s"Can't copy proxies ", err)
              currentState.sourceFileProxies match {
                case Some(proxyList)=>deleteCopiedProxies(currentState, proxyList)
                case None=> //don't need to do anything
              }
              sender() ! StepFailed(currentState, err.toString)
          }
        case None=>
          sender() ! StepFailed(currentState, "No source proxy list available")
      }

    case RollbackStep(currentState)=>
      implicit val s3Client = s3ClientManager.getS3Client(region=Some(currentState.destRegion),profileName=config.getOptional[String]("externalData.awsProfile"))
      currentState.sourceFileProxies match {
        case Some(proxyList) =>
          logger.info(s"Rolling back proxy copy for $proxyList")
          deleteCopiedProxies(currentState, proxyList)
          sender() ! StepSucceeded(currentState)
        case None=>
          sender() ! StepFailed(currentState, "Can't rollback proxy copy as there were no proxies copied")
      }
  }
}
