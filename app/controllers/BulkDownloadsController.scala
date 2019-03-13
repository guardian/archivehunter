package controllers

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Keep, Sink, Source}
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
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader, Indexer}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, S3ClientManager}
import helpers.SearchHitToArchiveEntryFlow

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class BulkDownloadsController @Inject()(config:Configuration,serverTokenDAO: ServerTokenDAO,
                                        lightboxBulkEntryDAO: LightboxBulkEntryDAO,
                                        lightboxEntryDAO: LightboxEntryDAO,
                                        esClientManager: ESClientManager,
                                        s3ClientManager: S3ClientManager,
                                        cc:ControllerComponents)(implicit system:ActorSystem)
  extends AbstractController(cc) with Circe with ArchiveEntryHitReader {

  private val logger = Logger(getClass)

  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

  val tokenLongDuration = config.getOptional[Int]("serverToken.longLivedDuration").getOrElse(7200)  //default value is 2 hours

  private val profileName = config.getOptional[String]("externalData.awsProfile")
  protected implicit val esClient = esClientManager.getClient()

  private val indexer = new Indexer(config.get[String]("externalData.indexName"))

  private implicit val mat:Materializer = ActorMaterializer.create(system)

  private def errorResponse(updatedToken:ServerTokenEntry) = serverTokenDAO
    .put(updatedToken)
    .map(saveResult=>{
      Forbidden(GenericErrorResponse("forbidden","invalid or expired token").asJson)
    }).recover({
    case err:Throwable=>
      logger.error(s"Could not update token: ", err)
      Forbidden(GenericErrorResponse("forbidden","invalid or expired token").asJson)
  })

  /**
    * bring back a list of all entries for the given bulk. This _may_ need pagination in future but right now we just get
    * everything
    * @param bulkEntry bulkEntry identifying the objects to query
    * @return a Future, containing a sequence of ArchiveEntryDownloadSynopsis
    */
  protected def entriesForBulk(bulkEntry:LightboxBulkEntry) = {
    val query = search(indexName) query {
          nestedQuery("lightboxEntries", {
            matchQuery("lightboxEntries.memberOfBulk", bulkEntry.id)
          })
        } sortBy fieldSort("path.keyword") scroll "1m"

    val source = Source.fromPublisher(esClient.publisher(query))
    val hitConverter = new SearchHitToArchiveEntryFlow()
    val sink = Sink.fold[Seq[ArchiveEntryDownloadSynopsis], ArchiveEntryDownloadSynopsis](Seq())({ (acc,entry)=>
      acc ++ Seq(entry)
    })
    source.via(hitConverter).map(entry=>ArchiveEntryDownloadSynopsis.fromArchiveEntry(entry)).toMat(sink)(Keep.right).run()
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
        logger.error(s"Could not verify one-time token: ${err.toString}")
        Future(Forbidden(GenericErrorResponse("forbidden", "invalid or expired token").asJson))
      case Some(Right(token))=>
        val updatedToken = token.updateCheckExpired(maxUses=Some(1)).copy(uses = token.uses+1)
        if(updatedToken.expired){
          errorResponse(updatedToken)
        } else {
          token.associatedId match {
            case None=>
              logger.error(s"Token ${token.value} has no bulk associated with it!")
              errorResponse(updatedToken)
            case Some(associatedId)=>
              lightboxBulkEntryDAO.entryForId(UUID.fromString(associatedId)).flatMap({
                case Some(Left(err))=>errorResponse(updatedToken)
                case None=>errorResponse(updatedToken)
                case Some(Right(bulkEntry))=>
                  serverTokenDAO
                    .put(updatedToken)
                    .flatMap(saveResult=>{
                      entriesForBulk(bulkEntry).flatMap(results=> {
                        val retrievalToken = ServerTokenEntry.create(duration = tokenLongDuration) //create a 2 hour token to cover the download.
                        serverTokenDAO.put(retrievalToken).map({
                          case None =>
                            Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, results).asJson)
                          case Some(Right(_)) =>
                            Ok(BulkDownloadInitiateResponse("ok", bulkEntry, retrievalToken.value, results).asJson)
                          case Some(Left(err)) =>
                            logger.error(s"Could not save retrieval token: $err")
                            InternalServerError(GenericErrorResponse("db_error", s"Could not save retrieval token: $err").asJson)
                        })
                      }).recoverWith({
                        case err:Throwable=>
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
