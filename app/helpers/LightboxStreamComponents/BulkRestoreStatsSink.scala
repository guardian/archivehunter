package helpers.LightboxStreamComponents

import akka.actor.ActorRef
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntry
import javax.inject.{Inject, Named}
import models.BulkRestoreStats
import akka.pattern.ask
import play.api.Logger
import services.GlacierRestoreActor._

import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/**
  * an Akka streams sink that takes in a stream of LightboxEntries, checks the archive status on them and at the end
  * materializes an object of [[BulkRestoreStats]] showing what state they are in.
  * you should get hold of this using an injector, i.e. val sinkFactory = injector.getInstance(classOf[BulkRestoreStatsSink])
  *
  * @param glacierRestoreActor actorRef pointing to GlacierRestoreActor. Get this using an injector.
  */
class BulkRestoreStatsSink @Inject() (@Named("glacierRestoreActor") glacierRestoreActor:ActorRef) extends GraphStageWithMaterializedValue[SinkShape[LightboxEntry], Future[BulkRestoreStats]]{
  private final val in:Inlet[LightboxEntry] = Inlet("BulkRestoreStatsSink.in")
  implicit val actorTimeout:akka.util.Timeout = 60 seconds

  override def shape: SinkShape[LightboxEntry] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[BulkRestoreStats]) = {
    val logger = Logger(getClass)

    val completionPromise = Promise[BulkRestoreStats]

    var currentStats:BulkRestoreStats = BulkRestoreStats.empty
    val logic = new GraphStageLogic(shape) {
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          val result = Try { Await.result((glacierRestoreActor ? CheckRestoreStatus(elem)).mapTo[GRMsg], 60 seconds) }

          result match {
            case Failure(err)=>
              logger.error(s"Could not check archive status on ${elem.fileId}", err)
              failStage(err)
            case Success(NotInArchive(entry))=>
              logger.info(s"${elem.fileId} is not in Glacier")
              currentStats = currentStats.copy(unneeded = currentStats.unneeded+1)
            case Success(RestoreNotRequested(entry))=>
              logger.info(s"${elem.fileId} was not requested")
              currentStats = currentStats.copy(notRequested = currentStats.notRequested+1)
            case Success(RestoreInProgress(entry))=>
              logger.info(s"${elem.fileId} is currently restoring")
              currentStats = currentStats.copy(inProgress = currentStats.inProgress+1)
            case Success(RestoreCompleted(entry, expiry))=>
              logger.info(s"${elem.fileId} is available")
              currentStats = currentStats.copy(available = currentStats.available+1)
            case Success(RestoreFailure(err))=>
              logger.error(s"Could not check archive status on ${elem.fileId}", err)
              failStage(err)
          }

          pull(in)
        }
      })

      override def preStart(): Unit = {
        pull(in)
      }

      override def postStop(): Unit = {
        completionPromise.complete(Success(currentStats))
      }
    }

    (logic, completionPromise.future)
  }
}
