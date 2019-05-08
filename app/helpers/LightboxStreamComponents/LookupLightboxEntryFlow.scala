package helpers.LightboxStreamComponents

import akka.actor.ActorSystem
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.gu.scanamo.error.DynamoReadError
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, LightboxEntryDAO}
import models.UserProfile
import play.api.Logger

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Similar to SaveLightboxEntryFlow, this flow looks up an existing lgihtbox entry for the given ArchiveEntry and outputs
  * the pair as a tuple
  */
class LookupLightboxEntryFlow (userProfile: UserProfile)(implicit val lightboxEntryDAO:LightboxEntryDAO, system:ActorSystem, esClient:HttpClient, indexer:Indexer)
  extends GraphStage[FlowShape[ArchiveEntry, (ArchiveEntry, LightboxEntry)]] {

  private val in:Inlet[ArchiveEntry] = Inlet.create("LookupLightboxEntryFlow.in")
  private val out:Outlet[(ArchiveEntry, LightboxEntry)] = Outlet.create("LookupLightboxEntryFlow.out")

  override def shape: FlowShape[ArchiveEntry, (ArchiveEntry, LightboxEntry)] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val logger = Logger(getClass)

    def retryGetEntry(elem:ArchiveEntry, retryCount:Int=0):Option[Either[DynamoReadError, LightboxEntry]] =
      Await.result(lightboxEntryDAO.get(userProfile.userEmail, elem.id), 60 seconds) match {
        case None=>
          logger.error(s"No lightbox entry found for archive entry ${elem.id}, but the archive entry has a lightbox bulk associated")
          None
        case Some(Left(err))=>
          logger.error(s"Could not look up lightbox entry for ${elem.id} on attempt $retryCount: $err")
          if(retryCount>100) throw new RuntimeException(s"Could not look up lightbox entry: $err")
          retryGetEntry(elem, retryCount+1)
        case success @ Some(Right(_))=>success
      }

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        retryGetEntry(elem) match {
          case None=>
            pull(in)
          case Some(Left(err))=>
            pull(in)
          case Some(Right(entry))=>
            push(out, (elem, entry))
        }

      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}
