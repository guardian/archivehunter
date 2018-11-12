package helpers

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import play.api.Logger

import scala.concurrent.Promise
import scala.util.Success

/**
  * Simple stream flow that completes a Promise when the stream stops.
  * @param completionPromise
  * @param cbData
  * @tparam T
  * @tparam U
  */
class EOSDetect[T,U] (completionPromise:Promise[T], cbData:T) extends GraphStage[FlowShape[U,U]]{
  private val in:Inlet[U] = Inlet.create("EOSDetect.in")
  private val out:Outlet[U] = Outlet.create("EOSDetect.out")

  override def shape: FlowShape[U, U] = FlowShape.of(in,out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val logger = Logger(getClass)

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        push(out, elem)
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })

    override def postStop(): Unit = {
      completionPromise.complete(Success(cbData))
      super.postStop()
    }
  }

}
