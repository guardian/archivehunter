package StreamComponents

import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry

/**
  * branches to either a "yes" or "no" port, depending on whether the incoming ArchiveEntry has a "valid"
  * MIME type.  For us, "valid" means that it is NOT null, application/binary, application/octet-stream or binary/octet-stream.
  * in practise, a "valid" MIME type means that we can switch on it to determine what proxies should be available.
  * an "invalid" MIME type means that we must fall back to checking the file extension.
  */
class MimeTypeBranch extends GraphStage[UniformFanOutShape[ArchiveEntry, ArchiveEntry]] {//GraphStage[FanOutShape2[ArchiveEntry, ArchiveEntry, ArchiveEntry]]{
  final val in:Inlet[ArchiveEntry] = Inlet.create("MimeTypeBranch.in")
  final val outYes:Outlet[ArchiveEntry] = Outlet.create("MimeTypeBranch.yes")
  final val outNo:Outlet[ArchiveEntry] = Outlet.create("MimeTypeBranch.no")

  //override def shape: FanOutShape2[ArchiveEntry, ArchiveEntry, ArchiveEntry] = FanOutShape.Ports(in,scala.collection.immutable.Seq(outYes,outNo))
  override def shape: UniformFanOutShape[ArchiveEntry, ArchiveEntry] = new UniformFanOutShape[ArchiveEntry, ArchiveEntry](in,Array(outYes,outNo))

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          //println(s"${elem.path} has MIME type ${elem.mimeType.toString}")
          if(elem.mimeType==null) { //it should never be, but just in case...
            push(outNo, elem)
          } else if(elem.mimeType.major=="application"){
            if(elem.mimeType.minor=="binary"){
              push(outNo, elem)
            } else if(elem.mimeType.minor=="octet-stream"){
              push(outNo, elem)
            } else {
              push(outYes, elem)
            }
          } else if(elem.mimeType.major=="binary"){
            if(elem.mimeType.minor=="octet-stream"){
              push(outNo,elem)
            } else {
              push(outYes, elem)
            }
          } else {
            push(outYes, elem)
          }
        }
      })

      setHandler(outNo, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("mimeTypeBranch NO: pullFromDownstream")
          if(!hasBeenPulled(in)) pull(in)
        }
      })

      setHandler(outYes, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("mimeTypeBranch YES: pullFromDownstream")
          if(!hasBeenPulled(in)) pull(in)
        }
      })
    }
}
