package StreamComponents

import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyType}
import models.ProxyVerifyResult

/**
  * branches to either a "yes" or "no" depending on whether we expect to have a proxy for the given MIME type
  */
class MimeTypeWantProxyBranch extends GraphStage[UniformFanOutShape[ArchiveEntry, ProxyVerifyResult]]{
  final val in:Inlet[ArchiveEntry] = Inlet.create("MimeTypeWantProxyBranch.in")
  final val outVideo:Outlet[ProxyVerifyResult] = Outlet.create("MimeTypeWantProxyBranch.outVideo")
  final val outAudio:Outlet[ProxyVerifyResult] = Outlet.create("MimeTypeWantProxyBranch.outAudio")
  final val outThumb:Outlet[ProxyVerifyResult] = Outlet.create("MimeTypeWantProxyBranch.outThumb")
  final val outNo:Outlet[ProxyVerifyResult] = Outlet.create("MimeTypeWantProxyBranch.no")

  override def shape: UniformFanOutShape[ArchiveEntry, ProxyVerifyResult] = new UniformFanOutShape[ArchiveEntry, ProxyVerifyResult](in, Array(outVideo,outAudio, outThumb, outNo))

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          if(elem.mimeType.major=="video"){
            push(outVideo, ProxyVerifyResult(elem.id, ProxyType.VIDEO, wantProxy = true))
            push(outAudio, ProxyVerifyResult(elem.id, ProxyType.AUDIO, wantProxy = false))
            push(outThumb, ProxyVerifyResult(elem.id, ProxyType.THUMBNAIL, wantProxy = true))
          } else if(elem.mimeType.major=="audio"){
            push(outVideo, ProxyVerifyResult(elem.id, ProxyType.VIDEO, wantProxy = false))
            push(outAudio, ProxyVerifyResult(elem.id, ProxyType.AUDIO, wantProxy = true))
            push(outThumb, ProxyVerifyResult(elem.id, ProxyType.THUMBNAIL, wantProxy = true))
          } else if(elem.mimeType.major=="image") {
            push(outVideo, ProxyVerifyResult(elem.id, ProxyType.VIDEO, wantProxy = false))
            push(outAudio, ProxyVerifyResult(elem.id, ProxyType.AUDIO, wantProxy = false))
            push(outThumb, ProxyVerifyResult(elem.id, ProxyType.THUMBNAIL, wantProxy = true))
          } else if(elem.mimeType.major=="model") {
            if(elem.mimeType.minor=="vnd.mts"){ //MTS files (from older tapeless cams) get mis-identified as this
              push(outVideo, ProxyVerifyResult(elem.id, ProxyType.VIDEO, wantProxy = true))
              push(outAudio, ProxyVerifyResult(elem.id, ProxyType.AUDIO, wantProxy = false))
              push(outThumb, ProxyVerifyResult(elem.id, ProxyType.THUMBNAIL, wantProxy = true))
            } else {
              push (outNo, ProxyVerifyResult(elem.id, ProxyType.UNKNOWN, wantProxy = false))
            }
          } else {
            push (outNo, ProxyVerifyResult(elem.id, ProxyType.UNKNOWN, wantProxy = false))
          }
        }
      })

      setHandler(outNo, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("outThumb: pullFromDownstream")
          if(!hasBeenPulled(in)) pull(in)
        }
      })

      setHandler(outVideo, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("outThumb: pullFromDownstream")
          if(!hasBeenPulled(in)) pull(in)
        }
      })

      setHandler(outAudio, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("outThumb: pullFromDownstream")
          if(!hasBeenPulled(in)) pull(in)
        }
      })

      setHandler(outThumb, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("outThumb: pullFromDownstream")
          if(!hasBeenPulled(in)) pull(in)
        }
      })
    }
}
