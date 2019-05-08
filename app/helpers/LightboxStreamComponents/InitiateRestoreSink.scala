package helpers.LightboxStreamComponents

import akka.actor.ActorRef
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStage, GraphStageLogic, GraphStageWithMaterializedValue}
import com.google.inject.Inject
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, StorageClass}
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntry
import javax.inject.{Named, Singleton}
import play.api.Logger
import services.GlacierRestoreActor

import scala.concurrent.{Future, Promise}

/**
  * This Sink takes in a stream of Tuple2[ArchiveEntry, LightboxEntry] and will ask GlacierRestoreActor to restore the item,
  * if necessary. It materializes an Int of the count of items that it has actually requested a restore from.
  * Normally, use DI to get this object - val sink = injector.getInstance(classOf[InitiateRestoreSink])
  * @param glacierRestoreActor ActorRef to send the restore messages to
  */
@Singleton
class InitiateRestoreSink @Inject() (@Named("glacierRestoreActor") glacierRestoreActor: ActorRef )
  extends GraphStageWithMaterializedValue[SinkShape[(ArchiveEntry, LightboxEntry)], Future[Int]]{
  private final val in = Inlet[(ArchiveEntry, LightboxEntry)]("InitiateRestoreSink.in")

  override def shape: SinkShape[(ArchiveEntry, LightboxEntry)] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[Int]()

    val logic = new GraphStageLogic(shape) {
      private val logger = Logger(getClass)
      private var ctr:Int = 0

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          val archiveEntry = elem._1
          archiveEntry.storageClass match {
            case StorageClass.GLACIER =>
              logger.info(s"${archiveEntry.path} is marked as Glacier, probably needs restore.")
              //passing None as the expiry time means "use default"
              glacierRestoreActor ! GlacierRestoreActor.InitiateRestore(archiveEntry, elem._2, None)
              logger.info(s"${archiveEntry.path}: restore requested")
              ctr+=1
            case _ =>
              logger.info(s"${archiveEntry.path} does not need restore")
          }
          pull(in)
        }
      })

      override def preStart(): Unit = pull(in)
      override def postStop(): Unit = promise.success(ctr)
    }

    (logic, promise.future)
  }
}
