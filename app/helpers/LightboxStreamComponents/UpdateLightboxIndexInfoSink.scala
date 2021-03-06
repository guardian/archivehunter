package helpers.LightboxStreamComponents

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStage, GraphStageLogic, GraphStageWithMaterializedValue}
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntryDAO
import helpers.LightboxHelper
import models.UserProfile
import org.slf4j.MDC
import play.api.Logger

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * an akka Sink that adds incoming ArchiveEntry records to a bulk entry
  * @param bulkId ID of the bulk entry to add to
  * @param userProfile UserProfile object for the user doing the adding
  * @param lightboxEntryDAO implicitly provided Data Access Object for lightboxEntry
  * @param system implicitly provided ActorSystem
  * @param esClient implicitly provided HttpClient for Elastic Search
  * @param indexer implicitly provided Indexer instance
  */
class UpdateLightboxIndexInfoSink (bulkId:String,userProfile: UserProfile, userAvatarUrl:Option[String])
                  (implicit val lightboxEntryDAO:LightboxEntryDAO, system:ActorSystem, esClient:ElasticClient, indexer:Indexer)
  extends GraphStageWithMaterializedValue[SinkShape[ArchiveEntry], Future[Int]]{

  private val in = Inlet.create[ArchiveEntry]("BulkAddSink.in")

  override def shape: SinkShape[ArchiveEntry] = SinkShape.of(in)

  private implicit val ec:ExecutionContext =  system.dispatcher

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic,Future[Int]) = {
    val promise = Promise[Int]()

    val logic = new GraphStageLogic(shape) {
      private val logger = Logger(getClass)
      private var ctr:Int = 0

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          val response = Try { Await.result(LightboxHelper.updateIndexLightboxed(userProfile, userAvatarUrl, elem, Some(bulkId)), 30 seconds) }
          response match {
            case Success(_)=>
              logger.info("Saved lightbox entry")
              ctr+=1
              pull(in)
            case Failure(err)=>
              MDC.put("error",err.toString)
              logger.error(s"Could not update lightbox info: ${err.getMessage}", err)
              promise.failure(err)
              failStage(err)
          }
        }
      })

      override def preStart(): Unit = {
        pull(in)
      }

      override def postStop(): Unit = {
        promise.success(ctr)
      }
    }
    (logic, promise.future)
  }
}
