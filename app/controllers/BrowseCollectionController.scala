package controllers

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.amazonaws.services.s3.model.ListObjectsRequest
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.ScanTargetDAO
import javax.inject.Inject
import play.api.libs.circe.Circe
import play.api.{Configuration, Logger}
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{GenericErrorResponse, ObjectListResponse}
import io.circe.syntax._
import io.circe.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

class BrowseCollectionController @Inject() (config:Configuration,s3ClientMgr:S3ClientManager, scanTargetDAO:ScanTargetDAO,
                                            cc:ControllerComponents) extends AbstractController(cc) with Circe{
  private val logger=Logger(getClass)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val s3Client = s3ClientMgr.getClient(awsProfile)

  /**
    * get all of the "subfolders" ("common prefix" in s3 parlance) for the provided bucket, but only if it
    * is one that is registered as managed by us.
    * this is to drive the tree view in the browse window
    * @param collectionName s3 bucket to query
    * @param prefix parent folder to list. If none, then lists the root
    * @return
    */
  def getFolders(collectionName:String, prefix:Option[String]) = Action.async {
    scanTargetDAO.targetForBucket(collectionName).map({
      case Some(Left(err))=>
        logger.error(s"Could not verify bucket name $collectionName: $err")
        InternalServerError(GenericErrorResponse("db_error",err.toString).asJson)
      case None=>
        logger.error(s"Bucket $collectionName is not managed by us")
        BadRequest(GenericErrorResponse("not_registered", s"$collectionName is not a registered collection").asJson)
      case Some(target)=>
        val rq = new ListObjectsRequest().withBucketName(collectionName).withDelimiter("/")
        val finalRq = prefix match {
          case Some(p)=>rq.withPrefix(p)
          case None=>rq
        }
        try {
          val result = s3Client.listObjects(finalRq)
          logger.debug(s"Got result:")
          result.getObjectSummaries.asScala.foreach(summ => logger.debug(s"\t$summ"))
          Ok(ObjectListResponse("ok","folder",result.getCommonPrefixes.asScala, -1).asJson)
        } catch {
          case ex:Throwable=>
            logger.error("Could not list S3 bucket: ", ex)
            InternalServerError(GenericErrorResponse("error", ex.toString).asJson)
        }
    })
  }
}
