package controllers

import java.time.ZonedDateTime
import java.util.UUID
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.AskTimeoutException
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import auth.{BearerTokenAuth, Security}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{DynamoClientManager, ESClientManager, S3ClientManager}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.{AmazonS3Exception, GeneratePresignedUrlRequest, GetObjectMetadataRequest, ObjectMetadata}
import org.scanamo.{DynamoReadError, ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common._

import javax.inject.{Inject, Named, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses._

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import com.theguardian.multimedia.archivehunter.common.errors.{ExternalSystemError, NothingFoundError}
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModelDAO, ScanTargetDAO}
import helpers.ProxyLocator
import services.ProxiesRelinker
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.{ProxyGenerators, RequestType}
import org.slf4j.LoggerFactory
import play.api.cache.SyncCacheApi
import requests.ManualProxySet

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class ProxiesController @Inject()(override val config:Configuration,
                                  override val controllerComponents:ControllerComponents,
                                  ddbClientMgr: DynamoClientManager,
                                  esClientMgr:ESClientManager,
                                  s3ClientMgr:S3ClientManager,
                                  proxyGenerators: ProxyGenerators,
                                  override val bearerTokenAuth:BearerTokenAuth,
                                  override val cache:SyncCacheApi,
                                  @Named("proxiesRelinker") proxiesRelinker:ActorRef)
                                 (implicit actorSystem:ActorSystem, mat:Materializer, scanTargetDAO:ScanTargetDAO, jobModelDAO:JobModelDAO, proxyLocationDAO:ProxyLocationDAO)
  extends AbstractController(controllerComponents) with Circe with ProxyLocationEncoder with Security {
  import akka.pattern.ask

  override protected val logger=LoggerFactory.getLogger(getClass)

  private val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("archivehunter")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  protected val tableName:String = config.get[String]("proxies.tableName")
  private val table = Table[ProxyLocation](tableName)

  implicit val esClient = esClientMgr.getClient()
  implicit val timeout:Timeout = 55 seconds
  implicit val indexer = new Indexer(indexName)

  private val s3conn = s3ClientMgr.getClient(awsProfile)

  private implicit val ddbAsync = ddbClientMgr.getNewAsyncDynamoClient(awsProfile)
  private val scanamoAlpakka = ScanamoAlpakka(ddbAsync)

  type ProxyDataType = List[Either[DynamoReadError, ProxyLocation]]
  private val MakeProxyDataSink = Sink.fold[ProxyDataType, ProxyDataType](List())(_ ++ _)

  def proxyForId(fileId:String, proxyType:Option[String]) = IsAuthenticatedAsync { _=> _=>
      proxyType match {
        case None=>
          scanamoAlpakka
            .exec(table.query("fileId"===fileId))
            .runWith(Sink.head)
            .map(result=>{
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
          scanamoAlpakka
            .exec(table.get("fileId"===fileId and ("proxyType"===t.toUpperCase)))
            .runWith(Sink.head)
            .map({
            case None=>
              NotFound(GenericErrorResponse("not_found","No proxy was registered").asJson)
            case Some(Left(err))=>
              logger.error(s"Could not look up proxy for $fileId: ${err.toString}")
              InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
            case Some(Right(items))=>
              Ok(responses.ObjectGetResponse("ok","proxy_location",items).asJson)
          })
      }
  }

  def getAllProxyRefs(fileId:String) = IsAuthenticatedAsync { _=> _=>
    scanamoAlpakka
      .exec(table.query("fileId"===fileId))
      .runWith(MakeProxyDataSink)
      .map(results=>{
        val failures = results.collect({ case Left(err) => err})
        if(failures.nonEmpty){
          failures.foreach(err=>logger.error(s"Could not retrieve proxy reference: $err"))
          InternalServerError(GenericErrorResponse("db_error", failures.mkString(", ")).asJson)
        } else {
          val success = results.collect({ case Right(location)=>location})
          Ok(ObjectListResponse("ok","ProxyLocation",success, success.length).asJson)
        }
      })
  }

  def getPlayable(fileId:String, proxyType:Option[String]) = IsAuthenticatedAsync { _=> _=>
      val actualType = proxyType match {
        case None=>"VIDEO"
        case Some(t)=>t.toUpperCase
      }

      scanamoAlpakka
        .exec(table.get("fileId"===fileId and ("proxyType"===actualType)))
        .runWith(Sink.head)
        .map({
          case None=>
            NotFound(GenericErrorResponse("not_found",s"no $proxyType proxy found for $fileId").asJson)
          case Some(Right(proxyLocation))=>
            implicit val s3client = s3ClientMgr.getS3Client(awsProfile, proxyLocation.region)
            val expiration = new java.util.Date()
            expiration.setTime(expiration.getTime + (1000 * 60 * 60)) //expires in 1 hour

            if(s3client.doesObjectExist(proxyLocation.bucketName, proxyLocation.bucketPath)) {
              val meta = s3client.getObjectMetadata(proxyLocation.bucketName, proxyLocation.bucketPath)
              val mimeType = MimeType.fromString(meta.getContentType) match {
                case Left(str) =>
                  logger.warn(s"Could not get MIME type for s3://${proxyLocation.bucketName}/${proxyLocation.bucketPath}: $str")
                  MimeType("application", "octet-stream")
                case Right(t) => t
              }
              val rq = new GeneratePresignedUrlRequest(proxyLocation.bucketName, proxyLocation.bucketPath)
                .withMethod(HttpMethod.GET)
                .withExpiration(expiration)
              val result = s3client.generatePresignedUrl(rq)
              Ok(PlayableProxyResponse("ok", result.toString, mimeType).asJson)
            } else {
              logger.warn(s"Invalid proxy location: $proxyLocation does not point to an existing file")
              NotFound(GenericErrorResponse("invalid_location",s"No proxy found for $proxyType on $fileId").asJson)
            }
          case Some(Left(err))=>
            InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
        })
  }

  lazy val defaultRegion = config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1")

  /**
    * endpoint that performs a scan for potential proxies for the given file.
    * if there is only one result, it is automatically associated.
    * @param fileId ES index file ID
    * @return
    */
  def searchFor(fileId:String) = IsAuthenticatedAsync { _=> _=>
    val resultFuture = indexer.getById(fileId).flatMap(entry=>{
      implicit val s3client = s3ClientMgr.getS3Client(awsProfile, entry.region)
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
  def associate(maybeFileId:Option[String], proxyId:String) = IsAuthenticatedAsync { _=> _=>
    maybeFileId match {
      case None =>
        Future(BadRequest(GenericErrorResponse("bad_request", "you must specify fileId={es-id}").asJson))
      case Some(fileId) =>
        val proxyLocationFuture = proxyLocationDAO.getProxyByProxyId(proxyId).flatMap({
          case None => //no proxy with this ID in the database yet; do an S3 scan to try to find the requested id
            val potentialProxyOrErrorList = indexer.getById(fileId).flatMap(entry=>{
              implicit val s3client = s3ClientMgr.getS3Client(awsProfile, entry.region)
              ProxyLocator.findProxyLocation(entry)
            })
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

  def generateThumbnail(fileId:String) = IsAuthenticatedAsync { _=> _=>
    proxyGenerators.requestProxyJob(RequestType.THUMBNAIL, fileId, None).map({
      case Failure(NothingFoundError(objectType, msg))=>
        NotFound(GenericErrorResponse("not_found", msg.toString).asJson)
      case Failure(ExternalSystemError(systemName, msg))=>
        InternalServerError(GenericErrorResponse("error",s"Could not launch task: $msg").asJson)
      case Failure(genericError)=>
        InternalServerError(GenericErrorResponse("error", genericError.toString).asJson)
      case Success(taskId)=>
        Ok(responses.ObjectGetResponse("ok","task",taskId).asJson)
    })
  }

  def generateProxy(fileId:String, typeStr:String) = IsAuthenticatedAsync { _=> _=>
    try {
      val pt = ProxyType.withName(typeStr.toUpperCase)
      indexer.getById(fileId).flatMap(entry=>{
        val canContinue = entry.mimeType.major.toLowerCase match {
          case "application"=>
            if(entry.mimeType.minor.toLowerCase=="octet-stream"){
              //application/octet-stream could be anything, so let it go through.
              Right(true)
            } else {
              Left(s"Can't proxy media of type ${entry.mimeType.toString}")
            }
          case "binary"=>
            if(entry.mimeType.minor.toLowerCase=="octet-stream"){
              //application/octet-stream could be anything, so let it go through.
              Right(true)
            } else {
              Left(s"Can't proxy media of type ${entry.mimeType.toString}")
            }
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
            val requestType = pt match {
              case ProxyType.THUMBNAIL=>RequestType.THUMBNAIL
              case _=>RequestType.PROXY
            }
            proxyGenerators.requestProxyJob(requestType,entry,Some(pt)).map({
              case Success(jobId)=>
                Ok(TranscodeStartedResponse("transcode_started", jobId, None).asJson)
              case Failure(err)=>
                InternalServerError(GenericErrorResponse("not_started", err.toString).asJson)
            })
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

  def relinkAllProxies = IsAuthenticatedAsync { _=> _=>
    val jobId = UUID.randomUUID().toString
    val jobDesc = JobModel(jobId,"RelinkProxies",None,None,JobStatus.ST_PENDING,None,"global",None,SourceType.SRC_GLOBAL,None)

    jobModelDAO.putJob(jobDesc).map(_=> {
      proxiesRelinker ! ProxiesRelinker.RelinkAllRequest(jobId)
      Ok(responses.ObjectCreatedResponse("ok", "job", jobId).asJson)
    }).recover({
      case dberr:Throwable=>
        logger.error(s"Could not create job entry: ${dberr.getMessage}", dberr)
        InternalServerError(GenericErrorResponse("db_error",dberr.toString).asJson)
    })
  }

  def relinkProxiesForTarget(scanTargetName:String) = IsAuthenticatedAsync { _=> _=>
    val jobId = UUID.randomUUID().toString
    val jobDesc = JobModel(jobId, "RelinkProxies", Some(ZonedDateTime.now()), None, JobStatus.ST_RUNNING, None, scanTargetName, None, SourceType.SRC_SCANTARGET, None)

    jobModelDAO.putJob(jobDesc).flatMap(_=>{
        proxiesRelinker ! ProxiesRelinker.RelinkScanTargetRequest(jobId, scanTargetName)

        scanTargetDAO.withScanTarget(scanTargetName) { target =>
          val updatedScanTarget = target.withAnotherPendingJob(jobDesc.jobId)
          scanTargetDAO.put(updatedScanTarget)
        } map {
          case None =>
            Ok(responses.ObjectCreatedResponse("ok", "job", jobId).asJson)
          case Some(Left(err)) =>
            logger.error(s"Could not updated scan target: $err")
            InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
          case Some(Right(rec)) =>
            Ok(responses.ObjectCreatedResponse("ok", "job", jobId).asJson)
        }
    }).recover({
      case dberr:Throwable=>
        logger.error(s"Could not initiate relink for target $scanTargetName: ${dberr.getMessage}", dberr)
        InternalServerError(GenericErrorResponse("db_error", dberr.getMessage).asJson)
    })
  }

  def checkProxyExists(bucket:String, path:String):Try[Option[ObjectMetadata]] = {
    try {
      val result = s3conn.getObjectMetadata(bucket, path)
      Success(Some(result))
    } catch {
      case ex:AmazonS3Exception=>
        if(ex.getStatusCode==404){ //object does not exist
          Success(None)
        } else {
          Failure(ex)
        }
      case ex:Throwable=>
        Failure(ex)
    }
  }

  /**
    * this represents an endpoint that allows the manual association of a proxy uri to an item
    * you trigger it with a ManualProxySet request passed as Json
    * if the given item already has a proxy then a 409 Conflict is returned
    * if nothing can be read by the server at the proxy address given then 404 Not Found is returned
    * if the proxy is set then 200 OK is returned
    * otherwise a 400 for invalid data or a 500 if there is a server-side error
    * @return
    */
  def manualSet = IsAdminAsync(circe.json(2048)) { _=> request=>
      request.body.as[ManualProxySet].fold(
        failure =>
          Future(BadRequest(GenericErrorResponse("bad_request", failure.toString).asJson)),
        proxySetRequest => {
          proxyLocationDAO.getProxy(proxySetRequest.entryId, proxySetRequest.proxyType).flatMap({
            case Some(existingProxy) =>
              Future(Conflict(responses.ObjectCreatedResponse("proxy_exists", "proxy_id", existingProxy.proxyId).asJson))
            case None =>
              checkProxyExists(proxySetRequest.proxyBucket, proxySetRequest.proxyPath) match {
                case Success(None) => //proxy does not exist
                  Future(NotFound(GenericErrorResponse("no_proxy", "Requested proxy file does not exist").asJson))
                case Failure(err) =>
                  logger.error(s"Could not access proxy at s3://${proxySetRequest.proxyBucket}/${proxySetRequest.proxyType}: ", err)
                  Future(InternalServerError(GenericErrorResponse("error", err.toString).asJson))
                case Success(Some(proxyMeta)) => //proxy exists
                  val newLoc = ProxyLocation.fromS3(proxySetRequest.proxyBucket, proxySetRequest.proxyPath, proxySetRequest.entryId, proxyMeta, Some(proxySetRequest.region), proxySetRequest.proxyType)
                  proxyLocationDAO.saveProxy(newLoc).map(_=> {
                    Ok(ObjectCreatedResponse("ok", "proxy", newLoc.proxyId).asJson)
                  }).recover({
                    case err:Throwable =>
                      InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
                  })
              }
          })
        }
      )
    }


  def analyseMetadata(entryId:String) = IsAdminAsync { _=> _=>
    indexer.getById(entryId).flatMap(entry => {
      proxyGenerators
        .requestMetadataAnalyse(entry, config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1"))
        .map({
          case Left(err) =>
            logger.error(s"Could not request analyse: $err")
            InternalServerError(GenericErrorResponse("error", err).asJson)
          case Right(jobId) =>
            Ok(ObjectCreatedResponse("ok", "job", jobId).asJson)
        })
    }).recover({
      case err: Throwable =>
        logger.error("Could not request analyse: ", err)
        InternalServerError(GenericErrorResponse("error", err.toString).asJson)
    })
  }

  def deleteProxyFile(proxyLocation:ProxyLocation) = Try {
    s3conn.deleteObject(proxyLocation.bucketName, proxyLocation.bucketPath)
  }

  /**
    * manually delete the given proxy.
    * @param fileId file ID of the main media
    * @param inputProxyType type of proxy to delete.
    * @return
    */
  def manualDelete(fileId:String, inputProxyType:String)  = IsAdminAsync { _=> request=>
    try {
      val proxyType = ProxyType.withName(inputProxyType)
      proxyLocationDAO.getProxy(fileId,proxyType).flatMap({
        case None=>Future(NotFound(GenericErrorResponse("not_found","No proxy found").asJson))
        case Some(loc)=>
          deleteProxyFile(loc) match {
            case Success(_)=>
              proxyLocationDAO.deleteProxyRecord(fileId, proxyType).map(result=>
                Ok(ObjectCreatedResponse("deleted","proxy",s"${fileId}:${inputProxyType}").asJson)
              ).recoverWith({
                case err:Throwable=>
                  logger.error("Could not delete proxy record in database", err)
                  Future(InternalServerError(GenericErrorResponse("db_error",err.toString).asJson))
              })
            case Failure(err)=>
              logger.error("Could not delete proxy file: ", err)
              Future(InternalServerError(GenericErrorResponse("error", err.toString).asJson))
          }
      })
    } catch {
      case ex:Throwable=>
        Future(BadRequest(GenericErrorResponse("error",s"Did not recognise proxy type $inputProxyType").asJson))
    }
  }
}
