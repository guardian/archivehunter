package helpers.LightboxStreamComponents

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream._
import akka.stream.stage._
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, LightboxEntryDAO}
import helpers.LightboxHelper
import models.UserProfile
import play.api.Logger

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * an akka Flow that creates LightboxEntry records for each ArchiveEntry that it receives, and outputs the saved LightboxEntry and incoming ArchiveEntry as a tuple
  * @param bulkId ID of the bulk entry to add to
  * @param userProfile UserProfile object for the user doing the adding
  * @param lightboxEntryDAO implicitly provided Data Access Object for lightboxEntry
  * @param system implicitly provided ActorSystem
  * @param esClient implicitly provided HttpClient for Elastic Search
  * @param indexer implicitly provided Indexer instance
  */
class SaveLightboxEntryFlow (bulkId:String,userProfile: UserProfile)
                  (implicit val lightboxEntryDAO:LightboxEntryDAO, system:ActorSystem, esClient:HttpClient, indexer:Indexer)
  extends GraphStageWithMaterializedValue[FlowShape[ArchiveEntry,(ArchiveEntry, LightboxEntry)], Future[Int]]{

  private val in = Inlet.create[ArchiveEntry]("BulkAddSink.in")
  private val out = Outlet.create[(ArchiveEntry, LightboxEntry)]("BulkAddSink.out")
  override def shape: FlowShape[ArchiveEntry,(ArchiveEntry, LightboxEntry)] = FlowShape.of(in, out)

  private implicit val ec:ExecutionContext =  system.dispatcher

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic,Future[Int]) = {
    val promise = Promise[Int]()

    val logic = new GraphStageLogic(shape) {
      private val logger = Logger(getClass)
      private var ctr:Int = 0

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          val saveFuture = LightboxHelper.saveLightboxEntry(userProfile, elem, Some(bulkId)).recover({
            case err:Throwable=>Failure(err) //ensure that an outer exception is caught too
          })

          Await.result(saveFuture, 30 seconds) match {
            case Success(entry)=>
              logger.info("Saved lightbox entry")
              ctr+=1
              push(out, (elem, entry))
            case Failure(err)=>
              logger.error("Could not save lightbox entry", err)
              //fail the output immediately, this should get seen by the stream's user
              promise.failure(err)
              throw err
          }
        }
      })

      setHandler(out, new AbstractOutHandler {
        override def onPull(): Unit = pull(in)
      })

      override def postStop(): Unit = {
        //if we've already failed the promise no point generating random error messages
        if(!promise.isCompleted) promise.success(ctr)
      }
    }
    (logic, promise.future)
  }
}
