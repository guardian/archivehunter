package com.theguardian.multimedia.archivehunter.common.services

import java.net.URI
import java.time.ZonedDateTime
import java.util.UUID

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.elastictranscoder.AmazonElasticTranscoder
import com.amazonaws.services.elastictranscoder.model._
import com.gu.scanamo.Table
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, ETSClientManager, S3ClientManager}
import com.theguardian.multimedia.archivehunter.common.errors.{ExternalSystemError, NothingFoundError}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import javax.inject.Inject
import org.apache.logging.log4j.{LogManager, Logger}

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._

object ProxyGenerators {
  /**
    * try to find an applicable uri to use as the proxy source.  This will use the main media unless it's in the Glacier storage class;
    * otherwise it will try to find an existing proxy and use that.  Failing this, None will be returned
    * @param entry [[ArchiveEntry]] instance representing the entry to proxy
    * @param proxyLocationDAO implicitly provided [[ProxyLocationDAO]] object
    * @param ddbClient implicitly provided [[AmazonDynamoDBAsync]] object
    * @return
    */
  def getUriToProxy(entry: ArchiveEntry)(implicit proxyLocationDAO: ProxyLocationDAO, ddbClient:AmazonDynamoDBAsync, logger:Logger) = entry.storageClass match {
    case StorageClass.GLACIER=>
      logger.info(s"s3://${entry.bucket}/${entry.path} is in Glacier, can't proxy directly. Looking up any existing video proxy")
      proxyLocationDAO.getProxy(entry.id,ProxyType.VIDEO).flatMap({
        case None=>
          proxyLocationDAO.getProxy(entry.id,ProxyType.AUDIO).map({
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

  /**
    * checks existing pipelines in the account to try to find one that goes from the selected input to the selected
    * output bucket
    * @param inputBucket name of the required source bucket
    * @param outputBucket name of the required destination bucket
    * @return a Sequence containing zero or more pipelines. If no pipelines are found, the sequence is empty.
    */
  def findPipelineFor(inputBucket:String, outputBucket:String)(implicit etsClient:AmazonElasticTranscoder) = {
    def getNextPage(matches:Seq[Pipeline], pageToken: Option[String]):Seq[Pipeline] = {
      val rq = new ListPipelinesRequest()
      val updatedRq = pageToken match {
        case None=>rq
        case Some(token)=>rq.withPageToken(token)
      }

      val result = etsClient.listPipelines(updatedRq).getPipelines.asScala
      if(result.isEmpty){
        matches
      } else {
        matches ++ result.filter(p=>p.getOutputBucket==outputBucket && p.getInputBucket==inputBucket)
      }
    }

    Try {
      val initialResult = getNextPage(Seq(), None)
      initialResult.filterNot(p => p.getName.contains("archivehunter")) //filter out anything that is not ours
    }
  }

  def getPipelineStatus(pipelineId:String)(implicit etsClient:AmazonElasticTranscoder) = Try {
    val rq = new ReadPipelineRequest().withId(pipelineId)

    val result = etsClient.readPipeline(rq)
    result.getPipeline.getStatus
  }

  /**
    * kick of the creation of a pipeline. NOTE: the Pipeline object returned will not be usable until it's in an active state.
    * @param pipelineName name of the pipeline to create
    * @param inputBucket input bucket it should point to
    * @param outputBucket output bucket it should point to
    * @return
    */
  def createEtsPipeline(pipelineName:String, inputBucket:String, outputBucket:String)(implicit etsClient:AmazonElasticTranscoder, config:ArchiveHunterConfiguration, logger:Logger) = {
    val completionNotificationTopic = config.get[String]("proxies.completionNotification")
    val errorNotificationTopic = config.get[String]("proxies.errorNotification")
    val warningNotificationTopic = config.get[String]("proxies.warningNotification")
    val transcodingRole = config.get[String]("proxies.transcodingRole")

    val createRq = new CreatePipelineRequest()
      .withInputBucket(inputBucket)
      .withName(pipelineName)
      .withNotifications(new Notifications().withCompleted(completionNotificationTopic).withError(errorNotificationTopic).withWarning(warningNotificationTopic))
      .withOutputBucket(outputBucket)
      .withRole(transcodingRole)

    Try {
      val result = etsClient.createPipeline(createRq)
      val warnings = result.getWarnings.asScala
      if(warnings.nonEmpty){
        logger.warn("Warnings were receieved when creating pipeline:")
        warnings.foreach(warning=>logger.warn(warning.toString))
      }
      result.getPipeline
    }
  }
}

class ProxyGenerators @Inject() (config:ArchiveHunterConfiguration, esClientMgr: ESClientManager, s3ClientMgr: S3ClientManager,
                                 ddbClientMgr: DynamoClientManager, scanTargetDAO: ScanTargetDAO, jobModelDAO:JobModelDAO,
                                 containerTaskMgr:ContainerTaskManager, etsClientMgr:ETSClientManager) extends ZonedDateTimeEncoder with ProxyLocationEncoder {
  private implicit val logger = LogManager.getLogger(getClass)

  private val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("archivehunter")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  protected val tableName:String = config.get[String]("proxies.tableName")
  private val table = Table[ProxyLocation](tableName)
  private val etsClient = etsClientMgr.getClient(awsProfile)

  import ProxyGenerators._

  /**
    * try to start a thumbnail proxy job.  This will use the main media if available; if it's in Glacier an existing proxy will be tried.
    * If no media is available without cost, will return a Failure(NothingFoundError).
    * @param fileId ES entry ID to proxy.
    * @return Success with the ARN of the ECS job ID if we suceeded, otherwise a Failure containing a [[com.theguardian.multimedia.archivehunter.common.errors.GenericArchiveHunterError]]
    *         describing the failure
    */
  def createThumbnailProxy(fileId:String):Future[Try[String]] = {
    implicit val client = esClientMgr.getClient()
    implicit val indexer = new Indexer(indexName)

    indexer.getById(fileId).flatMap(entry=>createThumbnailProxy(entry))
  }

  /**
    * try to start a thumbnail proxy job.  This will use the main media if available; if it's in Glacier an existing proxy will be tried.
    * If no media is available without cost, will return a Failure(NothingFoundError).
    * @param entry [[ArchiveEntry]] object describing the entry to thumbnail
    * @return Success with the ARN of the ECS job ID if we suceeded, otherwise a Failure containing a [[com.theguardian.multimedia.archivehunter.common.errors.GenericArchiveHunterError]]
    *         describing the failure
    */
  def createThumbnailProxy(entry: ArchiveEntry):Future[Try[String]] = {
    implicit val s3Client = s3ClientMgr.getS3Client(awsProfile)
    implicit val dynamoClient = ddbClientMgr.getClient(awsProfile)
    implicit val proxyLocationDAO = new ProxyLocationDAO(tableName)

    val callbackUrl=config.get[String]("proxies.appServerUrl")
    logger.info(s"callbackUrl is $callbackUrl")
    val jobUuid = UUID.randomUUID()

    val targetProxyBucketFuture = scanTargetDAO.targetForBucket(entry.bucket).map({
      case None=>throw new RuntimeException(s"Entry's source bucket ${entry.bucket} is not registered")
      case Some(Left(err))=>throw new RuntimeException(err.toString)
      case Some(Right(target))=>Some(target.proxyBucket)
    })

    val jobDesc = JobModel(UUID.randomUUID().toString,"thumbnail",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,entry.id,SourceType.SRC_MEDIA)

    val uriToProxyFuture = ProxyGenerators.getUriToProxy(entry)

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
          jobModelDAO.deleteJob(jobDesc.jobId)  //ignore the result, this is non-essential but there to prevent the jobs list getting clogged up
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
    }).recoverWith({
      case err:Throwable=>
        logger.error("Could not start proxy job: ", err)
        Future(Failure(ExternalSystemError("archivehunter", err.toString)))
    })
  }

  def startEtsProxy(toProxy:String, presetId:String, destinationBucket:String) = {
    val toProxyUri = URI.create(toProxy)

    findPipelineFor(toProxyUri.getHost,destinationBucket) match {
      case Success(matches)=>
        if(matches.nonEmpty) {
          logger.info(s"Found ${matches.length} pipelines for ${toProxyUri.getHost}->$destinationBucket, using the first")
          matches.head
        } else {
          logger.info(s"Found no matching pipelines for ${toProxyUri.getHost}->$destinationBucket, requesting creation")
          createEtsPipeline(s"archivehunter_", toProxyUri.getHost,destinationBucket)
          Failure()
        }
    }
    val jobRq = new CreateJobRequest()
      .withInput(new JobInput().withContainer("mp4").withKey(toProxyUri.getPath))
      .withOutput(new CreateJobOutput().withKey(makeOutputFilename(toProxyUri.getPath)).withPresetId(presetId))
      .withPipelineId(pipelineId)

    try {
      val result = etsClient.createJob(jobRq)
      Success(result.getJob.getId)
    } catch {
      case ex:Throwable=>
        Failure(ex)
    }
  }

  /**
    * try to start a media proxy
    * @param entry
    * @return
    */
  def createMediaProxy(entry: ArchiveEntry):Future[Try[String]] = {
    implicit val s3Client = s3ClientMgr.getS3Client(awsProfile)
    implicit val dynamoClient = ddbClientMgr.getClient(awsProfile)
    implicit val proxyLocationDAO = new ProxyLocationDAO(tableName)

    val callbackUrl=config.get[String]("proxies.appServerUrl")
    logger.info(s"callbackUrl is $callbackUrl")
    val jobUuid = UUID.randomUUID()

    val targetProxyBucketFuture = scanTargetDAO.targetForBucket(entry.bucket).map({
      case None=>throw new RuntimeException(s"Entry's source bucket ${entry.bucket} is not registered")
      case Some(Left(err))=>throw new RuntimeException(err.toString)
      case Some(Right(target))=>Some(target.proxyBucket)
    })

    Future.sequence(Seq(targetProxyBucketFuture, getUriToProxy(entry))).flatMap(results=> {
      val targetProxyBucket = results.head.get
      val maybeUriToProxy = results(1)
      logger.info(s"Target proxy bucket is $targetProxyBucket")
      logger.info(s"Source media is $maybeUriToProxy")
      maybeUriToProxy match {
        case None=>
          logger.error("Nothing found to proxy")
          Future(Failure(NothingFoundError("media", "Nothing found to proxy")))
        case Some(uriToProxy)=>
          val jobDesc = JobModel(UUID.randomUUID().toString,"proxy",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,entry.id,SourceType.SRC_MEDIA)

          jobModelDAO.putJob(jobDesc).map({
            case Some(Left(dynamoError))=>
              logger.error(s"Could not save new job description: $dynamoError")
              Failure(new RuntimeException(dynamoError.toString))
            case _=>  //either None or Some(Right(thing)) indicate success

              Success("hello")
          })
      }
    })
  }

}
