package StreamComponents

import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProxyVerifyResult
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyType}

/**
  * branches to either a "yes" or "no" depending on whether we expect to have a proxy for the given file type
  */
class FileTypeWantProxyBranch extends GraphStage[UniformFanOutShape[ArchiveEntry, ProxyVerifyResult]]{
  final val in:Inlet[ArchiveEntry] = Inlet.create("FileTypeWantProxyBranch.in")
  final val outVideo:Outlet[ProxyVerifyResult] = Outlet.create("FileTypeWantProxyBranch.outVideo")
  final val outAudio:Outlet[ProxyVerifyResult] = Outlet.create("FileTypeWantProxyBranch.outAudio")
  final val outThumb:Outlet[ProxyVerifyResult] = Outlet.create("FileTypeWantProxyBranch.outThumb")
  final val outNo:Outlet[ProxyVerifyResult] = Outlet.create("FileTypeWantProxyBranch.no")

  val shouldProxyExtensionsVideo:Array[String] = Array("mpg","mpe","mp2","mp4","avi","mov","mxf","mkv","mts")
  val shouldProxyExtensionsAudio:Array[String] = Array("mp3","aif","aiff", "wav")
  val shouldProxyExtensionsThumb:Array[String] = Array("cr2","nef","jpg","tif","tiff","tga","dxr")

  override def shape: UniformFanOutShape[ArchiveEntry,ProxyVerifyResult] =
    new UniformFanOutShape[ArchiveEntry, ProxyVerifyResult](in, Array(outVideo, outAudio, outThumb, outNo))

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          println(s"${elem.path} has file extension ${elem.file_extension.map(_.toLowerCase)}")
          elem.file_extension.map(_.toLowerCase) match {
            case None=>
              push(outNo, ProxyVerifyResult(elem.id, ProxyType.UNKNOWN, wantProxy = false))
            case Some(xtn)=>
              if(shouldProxyExtensionsVideo.contains(xtn)){
                push(outVideo, ProxyVerifyResult(elem.id, ProxyType.VIDEO, wantProxy = true))
                push(outAudio, ProxyVerifyResult(elem.id, ProxyType.AUDIO, wantProxy = false))
                push(outThumb, ProxyVerifyResult(elem.id, ProxyType.THUMBNAIL, wantProxy = true))
              } else if(shouldProxyExtensionsAudio.contains(xtn)){
                push(outVideo, ProxyVerifyResult(elem.id, ProxyType.VIDEO, wantProxy = false))
                push(outAudio, ProxyVerifyResult(elem.id, ProxyType.AUDIO, wantProxy = true))
                push(outThumb, ProxyVerifyResult(elem.id, ProxyType.THUMBNAIL, wantProxy = true))
              } else if(shouldProxyExtensionsThumb.contains(xtn)){
                push(outVideo, ProxyVerifyResult(elem.id, ProxyType.VIDEO, wantProxy = false))
                push(outAudio, ProxyVerifyResult(elem.id, ProxyType.AUDIO, wantProxy = false))
                push(outThumb, ProxyVerifyResult(elem.id, ProxyType.THUMBNAIL, wantProxy = true))
              } else {
                push(outNo, ProxyVerifyResult(elem.id, ProxyType.UNKNOWN, wantProxy = false))
              }
          }
        }
      })

      setHandler(outNo, new AbstractOutHandler {
        override def onPull(): Unit = {
          println("fileTypeWantProxyBranch NO: pullFromDownstream")
          if(!hasBeenPulled(in)) {
            pull(in)
          } else {
            println("input port not available")
          }
        }
      })

      setHandler(outVideo, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("fileTypeWantProxyBranch VIDEO: pullFromDownstream")
          if(!hasBeenPulled(in)){
            pull(in)
          } else {
            //println("input port not available")
          }
        }
      })

      setHandler(outAudio, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("fileTypeWantProxyBranch AUDIO: pullFromDownstream")
          if(!hasBeenPulled(in)){
            pull(in)
          } else {
            //println("input port not available")
          }
        }
      })

      setHandler(outThumb, new AbstractOutHandler {
        override def onPull(): Unit = {
          //println("fileTypeWantProxyBranch THUMB: pullFromDownstream")
          if(!hasBeenPulled(in)){
            pull(in)
          } else {
            //println("input port not available")
          }
        }
      })


    }
}
