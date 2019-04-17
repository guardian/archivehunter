package StreamComponents

import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProxyVerifyResult

class ProxyResultGroup extends GraphStage[FlowShape[ProxyVerifyResult, Seq[ProxyVerifyResult]]] {
  final val in:Inlet[ProxyVerifyResult] = Inlet.create("ProxyResultMerge.in")
  final val out:Outlet[Seq[ProxyVerifyResult]] = Outlet.create("ProxyResultMerge.out")

  override def shape: FlowShape[ProxyVerifyResult, Seq[ProxyVerifyResult]] = FlowShape.of(in,out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var cache:scala.collection.mutable.Map[String, scala.collection.mutable.Seq[ProxyVerifyResult]] =scala.collection.mutable.Map()
    val expectingProxyCount=3


    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        if(cache.contains(elem.fileId)){
          cache(elem.fileId) = cache(elem.fileId) ++ scala.collection.mutable.Seq(elem)
          if(cache(elem.fileId).length==expectingProxyCount){ //we have enough replies for this, we can make a judgement
            push(out, cache(elem.fileId))
          } else {  //we don't have enough replies so keep rolling
            pull(in)
          }
        } else {
          cache(elem.fileId) = scala.collection.mutable.Seq(elem)
          pull(in)
        }
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        //println("ProxyResultGroup: pull from downstream")
        pull(in)
      }
    })
  }
}
