package controllers

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.AskTimeoutException
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common._
import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses._

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._
import com.gu.scanamo.syntax._
import com.theguardian.multimedia.archivehunter.common.errors.{ExternalSystemError, NothingFoundError}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import helpers.ProxyLocator
import models._
import services.{ETSProxyActor, ProxiesRelinker}
import cmn_services.ProxyGenerators
import services.ETSProxyActor.{ETSMsg, ETSMsgReply, PreparationFailure, PreparationSuccess}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success}

@Singleton
class ProxiesController @Inject()(config:Configuration, cc:ControllerComponents, ddbClientMgr: DynamoClientManager,
                                  esClientMgr:ESClientManager, s3ClientMgr:S3ClientManager, proxyGenerators: ProxyGenerators,
                                  @Named("etsProxyActor") etsProxyActor:ActorRef,
                                  @Named("proxiesRelinker") proxiesRelinker:ActorRef)
                                 (implicit actorSystem:ActorSystem, scanTargetDAO:ScanTargetDAO, jobModelDAO:JobModelDAO)
  extends AbstractController(cc) with Circe with ProxyLocationEncoder {
  import akka.pattern.ask
  implicit private val mat:Materializer = ActorMaterializer.create(actorSystem)
  private val logger=Logger(getClass)

  private val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("archivehunter")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  protected val tableName:String = config.get[String]("proxies.tableName")
  private val table = Table[ProxyLocation](tableName)

  def proxyForId(fileId:String, proxyType:Option[String]) = Action.async {
    try {
      val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

      proxyType match {
        case None=>
          ScanamoAlpakka.exec(ddbClient)(table.query('fileId->fileId)).map(result=>{
            val failures = result.collect({case Left(err)=>err})
            if(failures.nonEmpty){
              logger.error(s"Could not look up proxy for $fileId: $failures")
              InternalServerError(GenericErrorResponse("error",failures.map(_.toString).mkString(", ")).asJson)
            } else {
              val output = result.collect({case Right(entry)=>entry})

              Ok(ObjectListResponse("ok","proxy_location",output, output.length).asJson)
            }
          })
        case Some(t)=>
          ScanamoAlpakka.exec(ddbClient)(table.get('fileId->fileId and ('proxyType->t.toUpperCase))).map({
            case None=>
              NotFound(GenericErrorResponse("not_found","No proxy was registered").asJson)
            case Some(Left(err))=>
              logger.error(s"Could not look up proxy for $fileId: ${err.toString}")
              InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
            case Some(Right(items))=>
              Ok(responses.ObjectGetResponse("ok","proxy_location",items).asJson)
          })
      }

    } catch {
      case ex:Throwable=>
        logger.error("Could not get dynamodb client: ", ex)
        Future(InternalServerError(GenericErrorResponse("dynamo error", ex.toString).asJson))
    }
  }

  def getPlayable(fileId:String, proxyType:Option[String]) = Action.async {
    try {
      val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)
      val s3client = s3ClientMgr.getS3Client(awsProfile)
      val actualType = proxyType match {
        case None=>"VIDEO"
        case Some(t)=>t.toUpperCase
      }

      ScanamoAlpakka.exec(ddbClient)(
        table.get('fileId->fileId and ('proxyType->actualType))
      ).map({
        case None=>
          NotFound(GenericErrorResponse("not_found",s"no $proxyType proxy found for $fileId").asJson)
        case Some(Right(proxyLocation))=>
          val expiration = new java.util.Date()
          expiration.setTime(expiration.getTime + (1000 * 60 * 60)) //expires in 1 hour

          val meta = s3client.getObjectMetadata(proxyLocation.bucketName, proxyLocation.bucketPath)
          val mimeType = MimeType.fromString(meta.getContentType) match {
            case Left(str)=>
              logger.warn(s"Could not get MIME type for s3://${proxyLocation.bucketName}/${proxyLocation.bucketPath}: $str")
              MimeType("application","octet-stream")
            case Right(t)=>t
          }
          val rq = new GeneratePresignedUrlRequest(proxyLocation.bucketName, proxyLocation.bucketPath)
            .withMethod(HttpMethod.GET)
            .withExpiration(expiration)
          val result = s3client.generatePresignedUrl(rq)
          Ok(PlayableProxyResponse("ok",result.toString,mimeType).asJson)
        case Some(Left(err))=>
          InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
      })
    } catch {
      case ex:Throwable=>
        logger.error(s"Could not get playable $proxyType for $fileId", ex)
        Future(InternalServerError(GenericErrorResponse("error",ex.toString).asJson))
    }
  }
  /**
    * endpoint that performs a scan for potential proxies for the given file.
    * if there is only one result, it is automatically associated.
    * @param fileId ES index file ID
    * @return
    */
  def searchFor(fileId:String) = Action.async {
    implicit val indexer = new Indexer(indexName)
    implicit val client = esClientMgr.getClient()
    implicit val s3Client = s3ClientMgr.getS3Client(awsProfile)
    implicit val dynamoClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)
    implicit val proxyLocationDAO = new ProxyLocationDAO(tableName)

    val resultFuture = indexer.getById(fileId).flatMap(entry=>{
      ProxyLocator.findProxyLocation(entry)
    })

    resultFuture
        .map(potentialProxiesResult=>{
          val failures = potentialProxiesResult.collect({case Left(err)=>err})
          if(failures.nonEmpty) throw new RuntimeException("Failed to get potential proxies")
          potentialProxiesResult.collect({case Right(potentialProxy)=>potentialProxy})
        })
      .map(potentialProxies=>{
        if(potentialProxies.length==1){
          //if we have an unambigous map, save it right away.
          indexer.getById(fileId).map(_.registerNewProxy(potentialProxies.head))
        }
        //otherwise, send the results back to the client
        Ok(ObjectListResponse("ok","potential_proxies", potentialProxies, potentialProxies.length).asJson)
      })
      .recover({
        case ex:Throwable=>
          logger.error("Could not search for proxy")
          InternalServerError(GenericErrorResponse("error", ex.toString).asJson)
      })
  }

  /**
    * endpoint to associate a given proxy with the item. ProxyId does not have to exist in the database yet;
    * if not, then all potential proxies for `fileId` are found and the ids checked off against ProxyId.
    * The idea is that from the frontend you can call searchFor, if this returns multiple entries you can call
    * `associate` to both save that specific item to the database and link it to the provided fileId
    * @param maybeFileId ES id of the file to associate with.  This is an Option to make it compatible with a URL parameter; passing
    *                    None simply results in a 400 Bad Request error.
    * @param proxyId Proxy ID of the proxy to link to fileId. Get this from `searchFor`.
    */
  def associate(maybeFileId:Option[String], proxyId:String) = Action.async {
    implicit val indexer = new Indexer(indexName)
    implicit val client = esClientMgr.getClient()
    implicit val s3Client = s3ClientMgr.getS3Client(awsProfile)
    implicit val dynamoClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)
    implicit val proxyLocationDAO = new ProxyLocationDAO(tableName)

    maybeFileId match {
      case None =>
        Future(BadRequest(GenericErrorResponse("bad_request", "you must specify fileId={es-id}").asJson))
      case Some(fileId) =>
        val proxyLocationFuture = proxyLocationDAO.getProxyByProxyId(proxyId).flatMap({
          case None => //no proxy with this ID in the database yet; do an S3 scan to try to find the requested id
            val potentialProxyOrErrorList = indexer.getById(fileId).flatMap(ProxyLocator.findProxyLocation(_))
            potentialProxyOrErrorList.map(_.collect({case Right(loc)=>loc})).map(_.find(_.proxyId==proxyId))

          case Some(proxyLocation) => //found it in the database
            Future(Some(proxyLocation.copy(fileId = fileId)))
        })

        proxyLocationFuture.flatMap({
          case None =>
            Future(NotFound(GenericErrorResponse("not_found", "No proxy could be found either in the database or matching given file id").asJson))
          case Some(proxyLocation) =>
            logger.debug(s"Got proxy location $proxyLocation")
            indexer
              .getById(fileId)
              .map(_.registerNewProxy(proxyLocation))
              .map(updated => Ok(ObjectCreatedResponse("registered", "proxy", proxyLocation).asJson))
        }).recover({
          case ex: Throwable =>
            logger.error("Could not associate proxy:", ex)
            InternalServerError(GenericErrorResponse("error", ex.toString).asJson)
        })
    }
  }

  def generateThumbnail(fileId:String) = Action.async {
    proxyGenerators.createThumbnailProxy(fileId).map({
      case Failure(NothingFoundError(objectType, msg))=>
        NotFound(GenericErrorResponse("not_found", msg.toString).asJson)
      case Failure(ExternalSystemError(systemName, msg))=>
        InternalServerError(GenericErrorResponse("error",s"Could not launch task: $msg").asJson)
      case Success(taskId)=>
        Ok(responses.ObjectGetResponse("ok","task",taskId).asJson)
    })
  }

  def generateProxy(fileId:String, typeStr:String) = Action.async {
    implicit val indexer = new Indexer(indexName)
    implicit val client = esClientMgr.getClient()
    implicit val timeout:Timeout = 55 seconds

    try {
      val pt = ProxyType.withName(typeStr.toUpperCase)
      indexer.getById(fileId).flatMap(entry=>{
        val canContinue = entry.mimeType.major.toLowerCase match {
          case "video"=>  //video can proxy to anything
            Right(true)
          case "audio"=>
            if(pt==ProxyType.VIDEO){
              Left("Can't make a video proxy of an audio item")
            } else {
              Right(true)
            }
          case "image"=>
            if(pt==ProxyType.AUDIO || pt==ProxyType.VIDEO){
              Left("Can't make audio or video proxy of an image item")
            } else {
              Right(true)
            }
          case _=>
            Left(s"Can't proxy media of type ${entry.mimeType.toString}")
        }

        canContinue match {
          case Right(_)=>
            if(pt==ProxyType.THUMBNAIL){
              proxyGenerators.createThumbnailProxy(entry).map({
                case Success(jobId)=>
                  Ok(TranscodeStartedResponse("transcode_started", jobId, None).asJson)
                case Failure(err)=>
                  InternalServerError(GenericErrorResponse("not_started", err.toString).asJson)
              })
            } else {
              val result = (etsProxyActor ? ETSProxyActor.CreateMediaProxy(entry, pt)).mapTo[ETSMsgReply]
              result.map({
                case PreparationSuccess(transcodeId, jobId)=>
                  Ok(TranscodeStartedResponse("transcode_started", jobId, Some(transcodeId)).asJson)
                case PreparationFailure(err)=>
                  InternalServerError(GenericErrorResponse("not_started", err.toString).asJson)
              })
            }
          case Left(err)=>
            Future(BadRequest(GenericErrorResponse("bad_request",err).asJson))
        }

      }).recoverWith({
        case timeout:AskTimeoutException=>
          logger.warn("Ask timed out: ", timeout)
          Future(Ok(GenericErrorResponse("warning", "proxy request timed out server-side, may not have started").asJson))
        case ex:Throwable=>
          logger.error("Could not trigger proxy: ", ex)
          Future(InternalServerError(GenericErrorResponse("error", ex.toString).asJson))
      })
    } catch {
      case ex:Throwable=>
        logger.error("Could not request proxy: ", ex)
        Future(BadRequest(GenericErrorResponse("bad_request", ex.toString).asJson))
    }
  }

  def relinkAllProxies = Action.async {
    val jobId = UUID.randomUUID().toString
    val jobDesc = JobModel(jobId,"RelinkProxies",None,None,JobStatus.ST_PENDING,None,"global",None,SourceType.SRC_GLOBAL)

    jobModelDAO.putJob(jobDesc).map({
      case None=>
        proxiesRelinker ! ProxiesRelinker.RelinkAllRequest(jobId)
        Ok(responses.ObjectCreatedResponse("ok","job",jobId).asJson)
      case Some(Left(dberr))=>
        logger.error(s"Could not create job entry: $dberr")
        InternalServerError(GenericErrorResponse("db_error",dberr.toString).asJson)
      case Some(Right(entry))=>
        proxiesRelinker ! ProxiesRelinker.RelinkAllRequest(jobId)
        Ok(responses.ObjectCreatedResponse("ok","job",jobId).asJson)
    })

  }
}
