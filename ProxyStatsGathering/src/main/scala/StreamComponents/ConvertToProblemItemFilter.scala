package StreamComponents

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ProblemItem, ProxyVerifyResult}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProxyHealth

/**
  * takes in a seq of ProxyVerifyResult and emits a ProblemItem _if_ any problems remain. Otherwise we drop the item
  * and pull the next one
  */
class ConvertToProblemItemFilter extends GraphStage[FlowShape[Seq[ProxyVerifyResult], ProblemItem]]{
  final val in:Inlet[Seq[ProxyVerifyResult]] = Inlet.create("ConvertToProblemItemFilter.in")
  final val out:Outlet[ProblemItem] = Outlet.create("ConvertToProblemItemFilter.out")

  override def shape: FlowShape[Seq[ProxyVerifyResult], ProblemItem] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    def makeDecision(elemts:Seq[ProxyVerifyResult]):ProxyHealth.Value = {
      val wanted = elemts.filter(_.wantProxy)
      if(wanted.isEmpty){
        ProxyHealth.NotNeeded
      } else {
        val have = wanted.filter(_.haveProxy.getOrElse(false))
        if(have.isEmpty){
          ProxyHealth.Unproxied
        } else if(have.length==wanted.length){
          ProxyHealth.Proxied
        } else {
          ProxyHealth.Partial
        }
      }
    }

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val resultSeq = grab(in)

        val location = resultSeq.head.extractLocation
        val falseentries = resultSeq.filter(entry=> !entry.wantProxy || entry.haveProxy.getOrElse(false))
        if(falseentries.length==resultSeq.length){
          println(s"WARNING: $resultSeq needs no proxies")
          pull(in)
        } else {
          val output = ProblemItem(resultSeq.head.fileId, location._1, location._2, esRecordSays = resultSeq.head.esRecordSays, resultSeq, Some(makeDecision(resultSeq)))
          push(out, output)
        }
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = pull(in)
    })
  }
}
