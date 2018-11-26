package com.theguardian.multimedia.archivehunter.common.services

import java.time.ZonedDateTime
import java.util.UUID

import com.gu.scanamo.Table
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.errors.{ExternalSystemError, NothingFoundError}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import javax.inject.Inject
import org.apache.logging.log4j.LogManager

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._

class ProxyGenerators @Inject() (config:ArchiveHunterConfiguration, esClientMgr: ESClientManager, s3ClientMgr: S3ClientManager,
                                 ddbClientMgr: DynamoClientManager, scanTargetDAO: ScanTargetDAO, jobModelDAO:JobModelDAO,
                                 containerTaskMgr:ContainerTaskManager) extends ZonedDateTimeEncoder with ProxyLocationEncoder {
  private val logger = LogManager.getLogger(getClass)

  private val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("archivehunter")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  protected val tableName:String = config.get[String]("proxies.tableName")
  private val table = Table[ProxyLocation](tableName)

  /**
    * try to start a thumbnail proxy job.  This will use the main media if available; if it's in Glacier an existing proxy will be tried.
    * If no media is available without cost, will return a Failure(NothingFoundError).
    * @param fileId ES entry ID to proxy.
    * @return Success with the ARN of the ECS job ID if we suceeded, otherwise a Failure containing a [[com.theguardian.multimedia.archivehunter.common.errors.GenericArchiveHunterError]]
    *         describing the failure
    */
  def createThumbnailProxy(fileId:String):Future[Try[String]] = {
    implicit val indexer = new Indexer(indexName)
    implicit val client = esClientMgr.getClient()
    implicit val s3Client = s3ClientMgr.getS3Client(awsProfile)
    implicit val dynamoClient = ddbClientMgr.getClient(awsProfile)
    implicit val proxyLocationDAO = new ProxyLocationDAO(tableName)

    val callbackUrl=config.get[String]("proxies.appServerUrl")
    logger.info(s"callbackUrl is $callbackUrl")
    val jobUuid = UUID.randomUUID()

    indexer.getById(fileId).flatMap(entry=>{
      val targetProxyBucketFuture = scanTargetDAO.targetForBucket(entry.bucket).map({
        case None=>throw new RuntimeException(s"Entry's source bucket ${entry.bucket} is not registered")
        case Some(Left(err))=>throw new RuntimeException(err.toString)
        case Some(Right(target))=>Some(target.proxyBucket)
      })

      val jobDesc = JobModel(UUID.randomUUID().toString,"thumbnail",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,fileId,SourceType.SRC_MEDIA)

      val uriToProxyFuture = entry.storageClass match {
        case StorageClass.GLACIER=>
          logger.info(s"s3://${entry.bucket}/${entry.path} is in Glacier, can't proxy directly. Looking up any existing video proxy")
          proxyLocationDAO.getProxy(fileId,ProxyType.VIDEO).flatMap({
            case None=>
              proxyLocationDAO.getProxy(fileId,ProxyType.AUDIO).map({
                case None=>None
                case Some(proxyLocation)=>
                  logger.info(s"Found audio proxy at s3://${proxyLocation.bucketName}/${proxyLocation.bucketPath}")
                  Some(s"s3://${proxyLocation.bucketName}/${proxyLocation.bucketPath}")
              })
            case Some(proxyLocation)=>
              logger.info(s"Found video proxy at s3://${proxyLocation.bucketName}/${proxyLocation.bucketPath}")
              Future(Some(s"s3://${proxyLocation.bucketName}/${proxyLocation.bucketPath}"))
          })
        case _=>
          logger.info(s"s3://${entry.bucket}/${entry.path} is in ${entry.storageClass}, will try to proxy directly")
          Future(Some(s"s3://${entry.bucket}/${entry.path}"))
      }

      Future.sequence(Seq(targetProxyBucketFuture, uriToProxyFuture)).map(results=> {
        logger.debug("Saving job description...")
        results ++ Seq(jobModelDAO.putJob(jobDesc))
      }).map(results=>{
        val targetProxyBucket = results.head.asInstanceOf[Option[String]].get
        logger.info(s"Target proxy bucket is $targetProxyBucket")
        logger.info(s"Source media is $uriToProxyFuture")
        results(1).asInstanceOf[Option[String]] match {
          case None =>
            logger.error("Nothing found to proxy")
            Failure(NothingFoundError("media", "Nothing found to proxy"))
          case Some(uriString) =>
            containerTaskMgr.runTask(
              command = Seq("/bin/bash","/usr/local/bin/extract_thumbnail.sh", uriString, targetProxyBucket, s"$callbackUrl/api/job/${jobDesc.jobId}/report"),
              environment = Map(),
              name = s"extract_thumbnail_${jobUuid.toString}",
              cpu = None
            ) match {
              case Success(task)=>
                logger.info(s"Successfully launched task: ${task.getTaskArn}")
                Success(task.getTaskArn)
              case Failure(err)=>
                logger.error("Could not launch task", err)
                Failure(ExternalSystemError("ECS", err.toString))
            }
        }
      })
    }).recoverWith({
      case err:Throwable=>
        logger.error("Could not start proxy job: ", err)
        Future(Failure(ExternalSystemError("archivehunter", err.toString)))
    })

  }
}
