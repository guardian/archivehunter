package helpers.LightboxStreamComponents

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntry

/**
  * simple flow stage that takes in a tuple of (ArchiveEntry, LightboxEntry) as yielded by
  * LookupLightboxEntryFlow/LookupArchiveEntryFromLBEntryFlow/ etc. and only returns the ArchiveEntry
  */
class ExtractArchiveEntry extends GraphStage[FlowShape[(ArchiveEntry, LightboxEntry),ArchiveEntry]]{
  private final val in:Inlet[(ArchiveEntry, LightboxEntry)] = Inlet("ExtractLightboxEntry.in")
  private final val out:Outlet[ArchiveEntry] = Outlet("ExtractLightboxEntry.out")

  override def shape: FlowShape[(ArchiveEntry, LightboxEntry), ArchiveEntry] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elems = grab(in)
        push(out, elems._1)
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = pull(in)
    })
  }
}
