package com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework

import java.time.{Instant, ZonedDateTime}
import java.util.UUID
import java.net.URLEncoder
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.elastictranscoder.model.CreatePipelineRequest
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sns.model.PublishRequest
import com.theguardian.multimedia.archivehunter.common.clientManagers._
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import com.theguardian.multimedia.archivehunter.common.errors.{ExternalSystemError, NothingFoundError}
import javax.inject.{Inject, Singleton}
import org.apache.logging.log4j.{LogManager, Logger}
import io.circe.syntax._
import io.circe.generic.auto._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global


@Singleton
class ProxyGenerators @Inject() (config:ArchiveHunterConfiguration,
                                 snsClientMgr:SNSClientManager,
                                 proxyLocationDAO: ProxyLocationDAO,
                                 s3ClientMgr:S3ClientManager,
                                 ddbClientManager:DynamoClientManager,
                                 esClientMgr:ESClientManager,
                                 stsClientMgr:STSClientManager,
                                 scanTargetDAO:ScanTargetDAO,
                                 jobModelDAO:JobModelDAO,
                                 proxyFrameworkInstanceDAO: ProxyFrameworkInstanceDAO)
                                (implicit system:ActorSystem) extends RequestModelEncoder {
  private implicit val logger = LogManager.getLogger(getClass)

  protected implicit val mat:Materializer = ActorMaterializer.create(system)

  protected val awsProfile = config.getOptional[String]("externalData.awsProfile")
  protected implicit val s3Client = s3ClientMgr.getClient(awsProfile)
  protected implicit val dynamoClient = ddbClientManager.getNewAlpakkaDynamoClient(awsProfile)
  protected implicit val esClient = esClientMgr.getClient()
  protected val indexer = new Indexer(config.get[String]("externalData.indexName"))


  protected def haveGlacierRestore(entry:ArchiveEntry)(implicit s3Client:AmazonS3) = Try {
    val meta =s3Client.getObjectMetadata(entry.bucket, entry.path)
    Option(meta.getOngoingRestore).map(_.booleanValue()) match {
      case None=> //no restore has been requested
        logger.debug("No restore requested")
        false
      case Some(true)=> //restore is ongoing but not ready
        logger.debug("Restore is ongoing")
        false
      case Some(false)=>
        Option(meta.getRestoreExpirationTime) match {
          case Some(expiry)=>
            if(expiry.toInstant.isAfter(Instant.now())){ //i.e., expiry.toInstant is greater than now()
              logger.debug("Restore has completed and item is available")
              true
            } else {
              logger.debug(s"Item expired at ${expiry.toString}")
              false
            }
          case None=>
            logger.warn("ongoing restore is FALSE but no expiration time - this is not expected")
            false
        }
    }
  }

  /**
    * try to find an applicable uri to use as the proxy source.  This will use the main media unless it's in the Glacier storage class;
    * otherwise it will try to find an existing proxy and use that.  Failing this, None will be returned
    * @param entry [[ArchiveEntry]] instance representing the entry to proxy
    * @param proxyLocationDAO implicitly provided [[ProxyLocationDAO]] object
    * @param ddbClient implicitly provided DynamoClient (alpakka dynamodb implementation) object
    * @return
    */
  def getUriToProxy(entry: ArchiveEntry) = entry.storageClass match {
    case StorageClass.GLACIER=>
      val url = s"s3://${URLEncoder.encode(entry.bucket,"UTF-8")}/${URLEncoder.encode(entry.path,"UTF-8")}"
      val haveRestore = haveGlacierRestore(entry) match {
        case Success(result)=>result
        case Failure(err)=>
          logger.error(err)
          false
      }
      if(haveRestore){
        logger.info(s"$url is in Glacier but has been restored. Trying to proxy directly.")
        Future(Some(url))
      } else {
        logger.info(s"$url is in Glacier, can't proxy directly. Looking up any existing video proxy")
        proxyLocationDAO.getProxy(entry.id, ProxyType.VIDEO).flatMap({
          case None =>
            proxyLocationDAO.getProxy(entry.id, ProxyType.AUDIO).map({
              case None => None
              case Some(proxyLocation) =>
                val proxUrl = s"s3://${URLEncoder.encode(proxyLocation.bucketName,"UTF-8")}/${URLEncoder.encode(proxyLocation.bucketPath,"UTF-8")}"
                logger.info(s"Found audio proxy at $proxUrl")
                Some(proxUrl)
            })
          case Some(proxyLocation) =>
            val proxUrl = s"s3://${URLEncoder.encode(proxyLocation.bucketName,"UTF-8")}/${URLEncoder.encode(proxyLocation.bucketPath,"UTF-8")}"
            logger.info(s"Found video proxy at $proxUrl")
            Future(Some(proxUrl))
        })
      }
    case _=>
      val url = s"s3://${URLEncoder.encode(entry.bucket,"UTF-8")}/${URLEncoder.encode(entry.path,"UTF-8")}"
      logger.info(s"$url is in ${entry.storageClass}, will try to proxy directly")
      Future(Some(url))
  }

  /**
    * checks the MIME type of the ArchiveEntry and returns the default proxy type for that kind of file, or None
    * if not recognised
    * @param entry ArchiveEntry instance
    * @return an Option of ProxyType
    */
  def defaultProxyType(entry:ArchiveEntry):Option[ProxyType.Value] = entry.mimeType.major match {
    case "video"=>Some(ProxyType.VIDEO)
    case "audio"=>Some(ProxyType.AUDIO)
    case "image"=>None
    case "application"=>
      if(entry.mimeType.minor=="octet-stream"){
        Some(ProxyType.VIDEO)
      } else {
        None
      }
    case "binary"=>
      if(entry.mimeType.minor=="octet-stream"){
        Some(ProxyType.VIDEO)
      } else {
        None
      }
    case _=>None
  }
  /**
    * try to start a thumbnail proxy job.  This will use the main media if available; if it's in Glacier an existing proxy will be tried.
    * If no media is available without cost, will return a Failure(NothingFoundError).
    * Convenience method for when no ArchiveEntry is available to the called; the fileId is looked up and then the main createThumbnailProxy is called
    * @param fileId ES entry ID to proxy.
    * @return Success with the ARN of the ECS job ID if we suceeded, otherwise a Failure containing a [[com.theguardian.multimedia.archivehunter.common.errors.GenericArchiveHunterError]]
    *         describing the failure
    */
  def requestProxyJob(requestType: RequestType.Value,fileId:String, proxyType: Option[ProxyType.Value])(implicit proxyLocationDAO:ProxyLocationDAO):Future[Try[String]] =
    indexer.getById(fileId).flatMap(entry=>requestProxyJob(requestType, entry, proxyType))

  /**
    * try to start a thumbnail proxy job.  This will use the main media if available; if it's in Glacier an existing proxy will be tried.
    * If no media is available without cost, will return a Failure(NothingFoundError).
    * @param entry [[ArchiveEntry]] object describing the entry to thumbnail
    * @return Success with the ARN of the ECS job ID if we suceeded, otherwise a Failure containing a [[com.theguardian.multimedia.archivehunter.common.errors.GenericArchiveHunterError]]
    *         describing the failure
    */
  def requestProxyJob(requestType: RequestType.Value,entry: ArchiveEntry, proxyType: Option[ProxyType.Value]):Future[Try[String]] = {
    val jobUuid = UUID.randomUUID()

    val targetFuture = scanTargetDAO.targetForBucket(entry.bucket).map({
      case None=>throw new RuntimeException(s"Entry's source bucket ${entry.bucket} is not registered")
      case Some(Left(err))=>throw new RuntimeException(err.toString)
      case Some(Right(target))=>target
    })

    val jobTypeString = requestType match {
      case RequestType.THUMBNAIL=>"thumbnail"
      case RequestType.PROXY=>"proxy"
      case RequestType.ANALYSE=>"analyse"
    }

    val jobDesc = JobModel(jobUuid.toString,jobTypeString,Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,entry.id,None,SourceType.SRC_MEDIA,None)
    val uriToProxyFuture = getUriToProxy(entry)

    internalDoProxy(jobDesc, requestType, proxyType, targetFuture, uriToProxyFuture)
  }

  private def internalDoProxy(jobDesc:JobModel, requestType:RequestType.Value, proxyType: Option[ProxyType.Value], targetFuture:Future[ScanTarget],uriToProxyFuture:Future[Option[String]]) =
    Future.sequence(Seq(targetFuture, uriToProxyFuture)).map(results=> {
      logger.debug("Saving job description...")
      results ++ Seq(jobModelDAO.putJob(jobDesc))
    }).flatMap(results=>{
      val target = results.head.asInstanceOf[ScanTarget]
      val targetProxyBucket = target.proxyBucket
      logger.info(s"Target proxy bucket is $targetProxyBucket")
      logger.info(s"Source media is $uriToProxyFuture")
      results(1).asInstanceOf[Option[String]] match {
        case None =>
          logger.error("Nothing found to proxy")
          jobModelDAO.deleteJob(jobDesc.jobId)  //ignore the result, this is non-essential but there to prevent the jobs list getting clogged up
          Future(Failure(NothingFoundError("media", "Nothing found to proxy")))
        case Some(uriString) =>
          val rq = RequestModel(requestType,uriString,targetProxyBucket,jobDesc.jobId,None,None,proxyType)
          sendRequest(rq, target.region)
      }
    }).recoverWith({
      case err:Throwable=>
        logger.error("Could not start proxy job: ", err)
        Future(Failure(ExternalSystemError("archivehunter", err.toString)))
    })

  /**
    * tries to kick off a "stuck in pending" job again by resubmitting
    * @param jobUuid UUId of the job to redo
    * @param proxyType the ProxyType is not stored, so we have to request it again here. if None, the default for the type of media
    *                  referenced by the job will be used
    * @return a Future with either an error string or the job ID as a string.
    */
  def rerunProxyJob(jobUuid:UUID, proxyType: Option[ProxyType.Value]) = {
    val entryFuture = jobModelDAO.jobForId(jobUuid.toString).flatMap({
      case None=>Future(Left(s"No job found for ${jobUuid.toString}"))
      case Some(Left(err))=>Future(Left(err.toString))
      case Some(Right(jobModel))=>
        indexer.getById(jobModel.sourceId).map(result=>Right((jobModel,result)))
    })

    entryFuture.flatMap({
      case Left(err)=>Future(Left(err))
      case Right(params)=>
        val jobModel = params._1
        val entry = params._2

        val requestType = jobModel.jobType match {
          case "thumbnail"=>Right(RequestType.THUMBNAIL)
          case "proxy"=>Right(RequestType.PROXY)
          case "analyse"=>Right(RequestType.ANALYSE)
          case other=>Left(s"$other is not a proxying job")
        }

        requestType match {
          case Right(actualRequestType)=>
            val targetFuture = scanTargetDAO.targetForBucket(entry.bucket).map({
              case None => throw new RuntimeException(s"Entry's source bucket ${entry.bucket} is not registered")
              case Some(Left(err)) => throw new RuntimeException(err.toString)
              case Some(Right(target)) => target
            })

            val uriToProxyFuture = getUriToProxy(entry)

            internalDoProxy(jobModel, requestType.right.get, proxyType, targetFuture, uriToProxyFuture).map({
              case Success(result)=>Right(result)
              case Failure(err)=>
                logger.error("Could not run proxying: ", err)
                Left(err.toString)
            })
          case Left(err)=>
            Future(Left(err))
        }
    })
  }

  /**
    * sends the given request to a Proxy Framework instance.
    * @param rq RequestModel that will be received by Proxy Framework
    * @param region region to look in
    * @return a Future with the message ID or a Failure if it errors
    */
  protected def sendRequest(rq:RequestModel,region:String) = {
    proxyFrameworkInstanceDAO.recordsForRegion(region).map(recordList=>{
      val failures = recordList.collect({case Left(err)=>err})
      if(failures.nonEmpty) throw new RuntimeException(s"Database error: $failures")

      val records = recordList.collect({case Right(rec)=>rec})
      if(records.isEmpty){
        throw new RuntimeException(s"No proxy transcode framework available for $region")
      } else if(records.length>1){
        logger.warn(s"Found ${records.length} different proxy framework instances for $region. Using ${records.head}")
      }

      val pfInst = records.head
      implicit val stsClient = stsClientMgr.getClientForRegion(awsProfile, region)
      snsClientMgr.getTemporaryClient(region, pfInst.roleArn).map(snsClient=>{
          val pubRq = new PublishRequest().withTopicArn(pfInst.inputTopicArn).withMessage(rq.asJson.toString)
          val result = snsClient.publish(pubRq)
          result.getMessageId
      })

    })
  }

  def updateJobFailed(jobDesc:JobModel,log:Option[String]) = {
    val updatedJob = jobDesc.copy(jobStatus = JobStatus.ST_ERROR,log=log)
    jobModelDAO.putJob(updatedJob)
  }

  protected def saveNSend(jobDesc:JobModel, rq:RequestModel, region:String, jobUuid:String) =
    jobModelDAO.putJob(jobDesc).flatMap({
      case None=>
        sendRequest(rq, region).map({
          case Success(msgId)=>
            Right(jobUuid)
          case Failure(err)=>
            logger.error(s"Could not send request to $region: ", err)
            updateJobFailed(jobDesc, Some(err.toString))
            Left(err.toString)
        })
      case Some(Right(updatedRecord))=>
        sendRequest(rq, region).map({
          case Success(msgId)=>Right(jobUuid)
          case Failure(err)=>
            logger.error(s"Could not send request to $region: ", err)
            updateJobFailed(jobDesc, Some(err.toString))
            Left(err.toString)
        })
      case Some(Left(err))=>
        logger.error("Could not save job: ", err)
        //no point in updating the job if it didn't save in the first place
        Future(Left(err.toString))
    })

  def requestCheckJob(sourceBucket:String, destBucket:String, region:String) = {
    val jobUuid = UUID.randomUUID()
    val jobDesc = JobModel(jobUuid.toString,"CheckSetup",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,"none",None,SourceType.SRC_GLOBAL,None)

    val rq = RequestModel(RequestType.CHECK_SETUP,s"s3://$sourceBucket",destBucket,jobUuid.toString,None,None,None)

    saveNSend(jobDesc,rq, region, jobUuid.toString)
  }

  def requestPipelineCreate(inputBucket:String,outputBucket:String,region:String,force:Boolean) = {
    val jobUuid = UUID.randomUUID()
    val jobDesc = JobModel(jobUuid.toString,"SetupTranscoding",Some(ZonedDateTime.now()),None,JobStatus.ST_PENDING,None,"none",None,SourceType.SRC_GLOBAL,None)

    val pipelineRequest = CreatePipeline(inputBucket,outputBucket)
    val rq = RequestModel(RequestType.SETUP_PIPELINE,"","",jobUuid.toString,Some(force),Some(pipelineRequest),None)

    saveNSend(jobDesc,rq, region, jobUuid.toString)
  }

  def requestMetadataAnalyse(entry:ArchiveEntry, defaultRegion:String) = {
    val jobUuid = UUID.randomUUID()

    val jobDesc = JobModel(jobUuid.toString,"Analyse",Some(ZonedDateTime.now()), None, JobStatus.ST_PENDING, None, entry.id, None, SourceType.SRC_MEDIA,None)
    val rq = RequestModel(RequestType.ANALYSE,s"s3://${entry.bucket}/${entry.path}","none",jobUuid.toString,None,None,None)

    saveNSend(jobDesc,rq, entry.region.getOrElse(defaultRegion), jobUuid.toString)
  }

}
