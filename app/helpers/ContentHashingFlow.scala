package helpers

import java.security.MessageDigest

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import akka.util.ByteString

class ContentHashingFlow(algo:String) extends GraphStage[FlowShape[ByteString,ByteString]]{
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
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })

    override def postStop(): Unit = {
      super.postStop()
      val result = digester.digest()
      push(out, ByteString(result))
    }
  }

}
