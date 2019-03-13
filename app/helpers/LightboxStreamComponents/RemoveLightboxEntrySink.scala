package helpers.LightboxStreamComponents

import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStage, GraphStageLogic, GraphStageWithMaterializedValue}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, LightboxEntryDAO}
import play.api.Logger

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.concurrent.duration._

class RemoveLightboxEntrySink (userEmail:String)(implicit lightboxEntryDAO:LightboxEntryDAO)
  extends GraphStageWithMaterializedValue[SinkShape[ArchiveEntry], Future[Int]]{

  final val in = Inlet[ArchiveEntry]("RemoveLightboxEntrySink.in")

  override def shape: SinkShape[ArchiveEntry] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic,Future[Int]) = {
    val promise = Promise[Int]
    var ctr:Int = 0

    (new GraphStageLogic(shape) {
      private val logger = Logger(getClass)

      setHandler(in, new AbstractInHandler{
        override def onPush(): Unit = {
          val elem = grab(in)

          val deleteFuture = lightboxEntryDAO.delete(userEmail, elem.id)
          deleteFuture.onComplete({
            case Success(result)=>
              logger.info(s"Deleted lightbox entry $elem")
              ctr+=1
            case Failure(err)=>
              logger.error(s"Could not delete lightbox entry $elem: ", err)
              promise.failure(err)
              throw err
          })

          Await.ready(deleteFuture, 30 seconds)
          pull(in)
        }
      })

      override def preStart(): Unit = pull(in)

      override def postStop(): Unit = promise.success(ctr)
    },promise.future)
  }
}
