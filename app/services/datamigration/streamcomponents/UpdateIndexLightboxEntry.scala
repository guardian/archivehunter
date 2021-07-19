package services.datamigration.streamcomponents

import akka.stream.scaladsl.GraphDSL
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, LightboxIndex}
import helpers.UserAvatarHelper

class UpdateIndexLightboxEntry(transformFunction:(String)=>Option[String], userAvatarHelper: UserAvatarHelper) extends GraphStage[FlowShape[ArchiveEntry, ArchiveEntry]] {
  private final val in:Inlet[ArchiveEntry] = Inlet.create("UpdateIndexLightboxEntry.in")
  private final val out:Outlet[ArchiveEntry] = Outlet.create("UpdateIndexLightboxEntry.in")

  override def shape: FlowShape[ArchiveEntry, ArchiveEntry] = FlowShape.of(in, out)

  def updateLightboxEntry(from:LightboxIndex):LightboxIndex =
    transformFunction(from.owner) match {
      case Some(updatedUserEmail)=>
        val updatedAvatarUrl = userAvatarHelper.getAvatarLocation(updatedUserEmail) //this does _not_ require the file to exist yet
        from.copy(
          owner = updatedUserEmail,
          avatarUrl = updatedAvatarUrl.map(_.toString)
        )
      case None=>
        from
    }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = pull(in)
    })

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        val updatedElem = elem.copy(lightboxEntries = elem.lightboxEntries.map(updateLightboxEntry))
        push(out, updatedElem)
      }
    })
  }
}

object UpdateIndexLightboxEntry {
  def apply(transformFunction:(String)=>Option[String], userAvatarHelper: UserAvatarHelper) = GraphDSL.create() { builder=>
    val f = builder.add(new UpdateIndexLightboxEntry(transformFunction, userAvatarHelper))
    FlowShape.of(f.in, f.out)
  }
}
