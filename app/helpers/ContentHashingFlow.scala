package helpers

import java.security.MessageDigest

import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import akka.util.ByteString

class ContentHashingFlow(algo:String) extends GraphStage[FlowShape[ByteString, ByteString]]{
  final val in:Inlet[ByteString] = Inlet.create("ContentHashingFlow.in")
  final val out:Outlet[ByteString] = Outlet.create("ContentHashingFlow.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val digester = MessageDigest.getInstance(algo)

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        digester.update(elem.toArray)
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        val result = digester.digest()
        push(out, ByteString(result))
      }
    })


    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(!isClosed(in)) {
          pull(in)
        } else {
          completeStage()
        }

      }
    })


  }

}
