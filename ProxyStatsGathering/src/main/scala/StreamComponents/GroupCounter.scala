package StreamComponents

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProxyVerifyResult
import models.GroupedResult
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProxyHealth

class GroupCounter extends GraphStage[FlowShape[Seq[ProxyVerifyResult], GroupedResult]]{
  final val in:Inlet[Seq[ProxyVerifyResult]] = Inlet.create("GroupCounter.in")
  final val out:Outlet[GroupedResult] = Outlet.create("GroupCounter.out")

  override def shape: FlowShape[Seq[ProxyVerifyResult], GroupedResult] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    def makeDecision(elemts:Seq[ProxyVerifyResult]):GroupedResult = {
      val wanted = elemts.filter(_.wantProxy)
      if(wanted.isEmpty){
        GroupedResult(elemts.head.fileId, esRecordSays=elemts.head.esRecordSays, result = ProxyHealth.NotNeeded)
      } else {
        val have = wanted.filter(_.haveProxy.getOrElse(false))
        if(have.isEmpty){
          GroupedResult(elemts.head.fileId, esRecordSays=elemts.head.esRecordSays, ProxyHealth.Unproxied)
        } else if(have.length==wanted.length){
          GroupedResult(elemts.head.fileId, esRecordSays=elemts.head.esRecordSays, ProxyHealth.Proxied)
        } else {
          GroupedResult(elemts.head.fileId, esRecordSays=elemts.head.esRecordSays, ProxyHealth.Partial)
        }
      }
    }

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem=grab(in)

        push(out, makeDecision(elem))
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}
