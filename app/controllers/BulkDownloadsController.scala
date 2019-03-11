package controllers

import java.util.UUID

import com.amazonaws.HttpMethod
import com.amazonaws.services.s3.model.{GeneratePresignedUrlRequest, ResponseHeaderOverrides}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxBulkEntry, LightboxBulkEntryDAO, LightboxEntryDAO}
import javax.inject.Inject
import models.{ArchiveEntryDownloadSynopsis, ServerTokenDAO, ServerTokenEntry}
import play.api.{Configuration, Logger}
import play.api.libs.circe.Circe
import play.api.mvc.{AbstractController, ControllerComponents}
import responses.{BulkDownloadInitiateResponse, GenericErrorResponse, ObjectGetResponse}
import io.circe.generic.auto._
import io.circe.syntax._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.searches.sort.SortOrder
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, Indexer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class BulkDownloadsController @Inject()(config:Configuration,serverTokenDAO: ServerTokenDAO,
                                        lightboxBulkEntryDAO: LightboxBulkEntryDAO,
                                        lightboxEntryDAO: LightboxEntryDAO,
                                        esClientManager: ESClientManager,
                                        s3ClientManager: S3ClientManager,
                                        cc:ControllerComponents)
  extends AbstractController(cc) with Circe with ArchiveEntryHitReader {

  private val logger = Logger(getClass)

  import com.sksamuel.elastic4s.http.ElasticDsl._

  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")
  private val profileName = config.getOptional[String]("externalData.awsProfile")
  protected implicit val esClient = esClientManager.getClient()

  private val indexer = new Indexer(config.get[String]("externalData.indexName"))

  private def errorResponse(updatedToken:ServerTokenEntry) = serverTokenDAO
    .put(updatedToken)
    .map(saveResult=>{
      Forbidden(GenericErrorResponse("forbidden","invalid or expired token").asJson)
    })

  protected def entriesForBulk(bulkEntry:LightboxBulkEntry) = esClient.execute {
    search(indexName) query {
      nestedQuery("lightboxEntries", {
        matchQuery("lightboxEntries.memberOfBulk", bulkEntry.id)
      })
    } sortBy fieldSort("path.keyword")
  }

  /**
    * take a one-time code generated in LightboxController, validate and expire it, then return the information of the
    * associated LightboxBulkEntry
    * @param codeValue value of the code
    * @return a Json response containing the metadata of the
    */
  def initiateWithOnetimeCode(codeValue:String) = Action.async {
    serverTokenDAO.get(codeValue).flatMap({
      case None=>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Left(err))=>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Right(token))=>
        val updatedToken = token.updateCheckExpired(maxUses=Some(1)).copy(uses = token.uses+1)
        if(updatedToken.expired){
          errorResponse(updatedToken)
        } else {
          token.associatedId match {
            case None=>errorResponse(updatedToken)
            case Some(associatedId)=>
              lightboxBulkEntryDAO.entryForId(UUID.fromString(associatedId)).flatMap({
                case Some(Left(err))=>errorResponse(updatedToken)
                case None=>errorResponse(updatedToken)
                case Some(Right(bulkEntry))=>
                  serverTokenDAO
                    .put(updatedToken)
                    .flatMap(saveResult=>{
                      entriesForBulk(bulkEntry).flatMap({
                        case Right(esResult)=>
                          val retrievalToken = ServerTokenEntry.create(duration=7200) //create a 2 hour token to cover the download.
                          serverTokenDAO.put(retrievalToken).map({
                            case None=>
                              Ok(BulkDownloadInitiateResponse("ok",bulkEntry,retrievalToken.value,esResult.result.to[ArchiveEntry].map(entry=>ArchiveEntryDownloadSynopsis.fromArchiveEntry(entry))).asJson)
                            case Some(Right(_))=>
                              Ok(BulkDownloadInitiateResponse("ok",bulkEntry,retrievalToken.value,esResult.result.to[ArchiveEntry].map(entry=>ArchiveEntryDownloadSynopsis.fromArchiveEntry(entry))).asJson)
                            case Some(Left(err))=>
                              logger.error(s"Could not save retrieval token: $err")
                              InternalServerError(GenericErrorResponse("db_error", s"Could not save retrieval token: $err").asJson)
                          })
                        case Left(err)=>
                          logger.error(s"Could not search index for bulk entries: $err")
                          Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
                      })
                    })
              })
          }
        }
    })
  }

  def getDownloadIdWithToken(tokenValue:String, fileId:String) = Action.async {
    serverTokenDAO.get(tokenValue).flatMap({
      case None=>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Left(err))=>
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Right(serverToken))=>
        indexer.getById(fileId).map(archiveEntry=>{
          val s3Client = s3ClientManager.getS3Client(profileName,archiveEntry.region)
            try {
              val rq = new GeneratePresignedUrlRequest(archiveEntry.bucket, archiveEntry.path, HttpMethod.GET)
                .withResponseHeaders(new ResponseHeaderOverrides().withContentDisposition("attachment"))
              val response = s3Client.generatePresignedUrl(rq)
              Ok(ObjectGetResponse("ok","link",response.toString).asJson)
            } catch {
              case ex:Throwable=>
                logger.error("Could not generate presigned s3 url: ", ex)
                InternalServerError(GenericErrorResponse("error",ex.toString).asJson)
            }
        })
    })
  }
}
