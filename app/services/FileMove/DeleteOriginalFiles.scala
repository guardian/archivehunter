package services.FileMove

import com.amazonaws.services.s3.AmazonS3
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.{DocId, Indexer, ProxyLocation}
import play.api.Configuration

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * final step, delete the original files from the disk(s)
  * @param s3ClientMgr An instance of [[S3ClientManager]] to obtain S3 client objects for the relevant region
  * @param indexer an instance of [[Indexer]] to access content in the main index
  */
class DeleteOriginalFiles(s3ClientMgr:S3ClientManager, indexer:Indexer, config:Configuration) extends GenericMoveActor with DocId {
  import GenericMoveActor._

  /**
    * verify that the object pointed to by (destBucket, destPath) is indeed an accurate copy of (srcBucket, srcPath).
    * this is an internal method which is called by `verifyNewMedia` and `verifyProxyFiles`
    * @param srcBucket bucket containing the source file
    * @param srcPath path to the source file
    * @param destBucket bucket containing the destination file
    * @param destPath path to the destination file
    * @return either a message explaining why the files don't match (one does not exist, size mistmatch, checksum mismatch) or a Right with no contents if they do
    */
  protected def verifyFile(srcBucket:String, srcPath:String, destBucket:String, destPath:String, sourceClient:AmazonS3, destClient:AmazonS3):Either[String, Unit] = {
    if(!destClient.doesObjectExist(destBucket, destPath)){
      return Left(s"Destination file s3://$destBucket/$destPath does not exist")
    }
    if(!sourceClient.doesObjectExist(srcBucket, srcPath)){
      return Left(s"Source file s3://$srcBucket/$srcPath does not exist")
    }

    val destFileMeta = destClient.getObjectMetadata(destBucket, destPath)
    val srcFileMeta = sourceClient.getObjectMetadata(srcBucket, srcPath)

    if(destFileMeta.getContentLength!=srcFileMeta.getContentLength){
      return Left(s"Destination size was ${destFileMeta.getContentLength} vs ${srcFileMeta.getContentLength}")
    }

    val maybeDestCS = Option(destFileMeta.getContentMD5)
    val maybeSrcCS  = Option(srcFileMeta.getContentMD5)
    if((maybeDestCS.isDefined && maybeSrcCS.isDefined) && maybeDestCS.get!=maybeSrcCS.get){
      return Left(s"Destination checksum ${maybeDestCS.get} is different to source ${maybeSrcCS.get}")
    } else if(maybeDestCS.isEmpty || maybeSrcCS.isEmpty){
      logger.warn("Either source or destination checksum could not be obtained from S3")
    }

    Right( () )
  }

  /**
    * verify that the new file is in place and that its size and checksum match the source
    * @param state [[FileMoveTransientData]] giving the state of the move
    * @return either a string indicating why the file is not good or the Unit value
    */
  def verifyNewMedia(state:FileMoveTransientData, sourceClient:AmazonS3, destClient:AmazonS3):Either[String,Unit] = {
    val archiveEntry = state.entry.get

    verifyFile(archiveEntry.bucket, archiveEntry.path, state.destBucket, archiveEntry.path, sourceClient, destClient)
  }

  /**
    * verify that each new proxy file listed in the state is in place and that its size and checksum match the source
    * @param state [[FileMoveTransientData]] giving the state of the move
    * @param s3Client implicitly provided s3Client for the correct region
    * @return either a string indicating why the file is not good or the Unit value
    */
  def verifyProxyFiles(state:FileMoveTransientData, sourceClient:AmazonS3, destClient:AmazonS3):Either[String,Unit] = {
    if (state.sourceFileProxies.isEmpty) {
      logger.warn(s"No source file proxies to verify")
      return Right(())
    }

    if (state.destFileProxy.isEmpty) {
      logger.error(s"Have ${state.sourceFileProxies.get.length} source proxies but no destination proxies defined")
      return Left(s"Have ${state.sourceFileProxies.get.length} source proxies but no destination proxies defined")
    }

    val srcProxies = state.sourceFileProxies.get
    val dstProxies = state.destFileProxy.get

    if (srcProxies.length != dstProxies.length) {
      return Left(s"Source had ${srcProxies.length} proxies but destination has ${dstProxies.length}")
    }

    /**
      * tail-recursively iterate the proxy lists and verify that all are present and correct
      * this assumes that srcProxyList and dstProxyList are in-sync
      * @param srcProxyList list of ProxyLocation for the source item
      * @param dstProxyList list of ProxyLocation for the destination
      * @return Right if both srcProxyList and dstProxyList match, otherwise Left with a description
      */
    def recursiveVerify(srcProxyList: Seq[ProxyLocation], dstProxyList: Seq[ProxyLocation]): Either[String, Unit] = {
      if (srcProxyList.isEmpty) return Right(())

      verifyFile(srcProxyList.head.bucketName, srcProxyList.head.bucketPath, dstProxyList.head.bucketName, dstProxyList.head.bucketPath, sourceClient, destClient) match {
        case Right(_) =>
          recursiveVerify(srcProxyList.tail, dstProxyList.tail)
        case problem@Left(_) => problem
      }
    }

    recursiveVerify(srcProxies, dstProxies)
  }

  /**
    * Delete the files in parallel. Contained in a Try to make it easier to catch _every_ failure not
    * just one
    * @param bucket bucket to delete from
    * @param path to the file to delete
    * @param s3Client implicitly provided S3 Client
    * @return a Future, containing a Try with either the result of the delete operation or an error
    */
  def tryToDelete(bucket:String, path:String)(implicit s3Client:AmazonS3) = Future {
    Try { s3Client.deleteObject(bucket, path) }
  }

  def deleteAllFor(state:FileMoveTransientData)(implicit s3Client:AmazonS3) = {
    val archiveEntry = state.entry.get

    val proxiesList = state.sourceFileProxies.getOrElse(Seq()).map(prx=>(prx.bucketName, prx.bucketPath))
    val allFilesList = proxiesList :+ (archiveEntry.bucket, archiveEntry.path)

    val completionFuture = Future.sequence(allFilesList.map(bucketpath=>tryToDelete(bucketpath._1, bucketpath._2)))

    completionFuture.map(results=>{
      val failures = results.collect({case Failure(err)=>err})
      if(failures.nonEmpty){
        logger.error(s"${failures.length} files failed to delete: ")
        failures.foreach(err=>logger.error(s"\tFailed to delete: ", err))
        Left(failures.head.getMessage)
      } else {
        logger.info(s"Deleted ${results.length} files")
        Right( () )
      }
    })
  }

  override def receive: Receive = {
    case PerformStep(state)=>
      //verify new media and proxy files. only if both match do the deletion
      val sourceClient:AmazonS3 = s3ClientMgr.getS3Client(profileName=config.getOptional[String]("externalData.awsProfile"),region=state.entry.flatMap(_.region))
      val destClient:AmazonS3 = s3ClientMgr.getS3Client(profileName=config.getOptional[String]("externalData.awsProfile"),region=Some(state.destRegion))
      verifyNewMedia(state, sourceClient, destClient).map(_=>verifyProxyFiles(state, sourceClient, destClient)) match {
        case Left(problem)=>
          sender() ! StepFailed(state, problem)
        case Right(_)=> //media and proxies both verified, can proceed to deletion.
          val originalSender = sender()
          deleteAllFor(state)(sourceClient).onComplete({
            case Success(Right(_))=>
              logger.info(s"Deletion completed")
              val updatedState = state.copy(sourceFileProxies = None)
              originalSender ! StepSucceeded(updatedState)
            case Success(Left(err))=>
              logger.error(s"Some or all deletions failed")
              originalSender ! StepFailed(state, err)
            case Failure(err)=>
              logger.error(s"Deletion thread(s) failed: ", err)
              originalSender ! StepFailed(state, err.getMessage)
          })
      }
    case RollbackStep(state)=>
      logger.error(s"Can't roll back file deletion!")
      sender() ! StepFailed(state, "Can't roll back file deletion!")
  }
}
