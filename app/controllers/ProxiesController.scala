package controllers

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common._
import helpers.{DynamoClientManager, ESClientManager, ProxyLocator, S3ClientManager}
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses._

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe.generic.auto._
import io.circe.syntax._
import com.gu.scanamo.syntax._
import com.sun.org.apache.xerces.internal.xs.datatypes.ObjectList
import models.ScanTargetDAO

import scala.concurrent.Future
import scala.util.{Failure, Success}

@Singleton
class ProxiesController @Inject()(config:Configuration, cc:ControllerComponents, ddbClientMgr: DynamoClientManager, esClientMgr:ESClientManager, s3ClientMgr:S3ClientManager)
                                 (implicit actorSystem:ActorSystem, scanTargetDAO:ScanTargetDAO)
  extends AbstractController(cc) with Circe with ProxyLocationEncoder {
  implicit private val mat:Materializer = ActorMaterializer.create(actorSystem)
  private val logger=Logger(getClass)

  private val indexName = config.getOptional[String]("elasticsearch.index").getOrElse("archivehunter")
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  protected val tableName:String = config.get[String]("proxies.tableName")
  private val table = Table[ProxyLocation](tableName)

  def proxyForId(fileId:String, proxyType:Option[String]) = Action.async {
    try {
      val ddbClient = ddbClientMgr.getNewAlpakkaDynamoClient(awsProfile)

      val actualType = proxyType match {
        case None=>"VIDEO"
        case Some(t)=>t.toUpperCase
      }

      ScanamoAlpakka.exec(ddbClient)(
        table.get('fileId->fileId and ('proxyType->actualType))
      ).map({
        case None=>NotFound(GenericErrorResponse("not_found","No proxy was registered").asJson)
        case Some(Left(err))=>
          logger.error(s"Could not look up proxy for $fileId: ${err.toString}")
          InternalServerError(GenericErrorResponse("db_error", err.toString).asJson)
        case Some(Right(entry))=>Ok(ObjectGetResponse("ok","proxy_location",entry).asJson)
      })

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
      })
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
            indexer.getById(fileId).flatMap(entry => {
              ProxyLocator.findProxyLocation(entry)
            }).map(_.find(_.proxyId == proxyId))
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
}
