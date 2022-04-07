package services.FileMove

import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.{DocId, ProxyLocation}
import play.api.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{CopyObjectRequest, DeleteObjectRequest, HeadObjectRequest}

import scala.util.{Failure, Success, Try}

/**
  * this actor copies a file to the requested destination bucket and updates the internal state with the new file ID.
  * when rolling back, it checks that the source file still exists and if so deletes the one it copied earlier.
  */
class CopyProxyFiles (s3ClientManager:S3ClientManager, config:Configuration) extends GenericMoveActor with DocId {
  import GenericMoveActor._
  import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._

  def deleteCopiedProxies(currentState:FileMoveTransientData, proxyList:Seq[ProxyLocation])(implicit s3Client:S3Client) =
    proxyList.foreach(loc=> {
      val deletion = for {
        exists <- s3Client.doesObjectExist(currentState.destProxyBucket, loc.bucketPath)
        result <- if (exists) {
          Try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(currentState.destProxyBucket).key(loc.bucketPath).build())
          }.map(Some.apply)
        } else {
          Success(None)
        }
      } yield result
      deletion match {
        case Success(_)=>
          logger.info(s"Deleted copied proxy at ${currentState.destProxyBucket}:${loc.bucketPath}")
        case Failure(err)=>
          logger.error("Could not delete copied proxy: ", err)
      }
    })

  override def receive: Receive = {
    case PerformStep(currentState)=>
      implicit val s3Client = s3ClientManager.getS3Client(region=Some(Region.of(currentState.destRegion)),profileName=config.getOptional[String]("externalData.awsProfile"))
      currentState.sourceFileProxies match {
        case Some(proxyList) =>
          try {
            val updatedProxyList = proxyList.map(loc=>
              loc.copy(fileId=currentState.destFileId.get,
                proxyId=makeDocId(currentState.destProxyBucket, loc.bucketPath),
                bucketName = currentState.destProxyBucket,
                region = Some(currentState.destRegion)
              )
            )

            proxyList.map(proxy => {
              logger.debug(s"Copying from ${proxy.bucketName}:${proxy.bucketPath} to ${currentState.destProxyBucket}:${proxy.bucketPath}")
              //does the proxy still exist?
              val copyResult = for {
                meta <- Try { s3Client.headObject(HeadObjectRequest.builder().bucket(proxy.bucketName).key(proxy.bucketPath).build()) }
                copyResult <- Try {
                  logger.info(s"Proxy ${proxy.bucketName}/${proxy.bucketPath} exists with size ${meta.contentLength()} and eTag ${meta.eTag()}")
                  s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(proxy.bucketName)
                    .sourceKey(proxy.bucketPath)
                    .destinationBucket(currentState.destProxyBucket)
                    .destinationKey(proxy.bucketPath)
                    .build()
                  )
                }
              } yield copyResult
              copyResult match {
                case Success(result)=>Some(result)
                case Failure(err)=>
                  logger.warn(s"Could not find proxy ${proxy.bucketName}/${proxy.bucketPath}: ${err.getMessage}.  Assuming that it does not exist any more.")
                  None
              }
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
      implicit val s3Client = s3ClientManager.getS3Client(region=Some(Region.of(currentState.destRegion)),profileName=config.getOptional[String]("externalData.awsProfile"))
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
