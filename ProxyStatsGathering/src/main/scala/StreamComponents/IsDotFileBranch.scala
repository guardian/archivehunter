package StreamComponents

import akka.stream.{Attributes, Inlet, Outlet, UniformFanOutShape}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry

class IsDotFileBranch extends GraphStage[UniformFanOutShape[ArchiveEntry,ArchiveEntry]] {
  final val in:Inlet[ArchiveEntry] = Inlet.create("IsDotFileBranch.in")
  final val outYes:Outlet[ArchiveEntry] = Outlet.create("IsDotFileBranch.yes")
  final val outNo:Outlet[ArchiveEntry] = Outlet.create("IsDotFileBranch.no")

  override def shape: UniformFanOutShape[ArchiveEntry, ArchiveEntry] = UniformFanOutShape(in,outYes,outNo)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem=grab(in)

        val pathParts = elem.path.split("/")
        val filename = pathParts.last
        if(filename.startsWith(".")){
          push(outYes, elem)
        } else {
          push(outNo, elem)
        }
      }
    })

    setHandler(outYes, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(!hasBeenPulled(in)) pull(in)
      }
    })

    setHandler(outNo, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(!hasBeenPulled(in)) pull(in)
      }
    })
  }
}
