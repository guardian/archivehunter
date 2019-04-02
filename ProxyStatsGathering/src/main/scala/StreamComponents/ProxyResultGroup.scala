package StreamComponents

import akka.stream._
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import models.{GroupedResult, ProxyResult, ProxyVerifyResult}

class ProxyResultGroup extends GraphStage[FlowShape[ProxyVerifyResult, GroupedResult]] {
  final val in:Inlet[ProxyVerifyResult] = Inlet.create("ProxyResultMerge.in")
  final val out:Outlet[GroupedResult] = Outlet.create("ProxyResultMerge.out")

  override def shape: FlowShape[ProxyVerifyResult, GroupedResult] = FlowShape.of(in,out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var cache:scala.collection.mutable.Map[String, scala.collection.mutable.Seq[ProxyVerifyResult]] =scala.collection.mutable.Map()
    val expectingProxyCount=3

    def makeDecision(elemts:Seq[ProxyVerifyResult]):GroupedResult = {
      val wanted = elemts.filter(_.wantProxy)
      if(wanted.isEmpty){
        GroupedResult(elemts.head.fileId,result = ProxyResult.NotNeeded)
      } else {
        val have = wanted.filter(_.haveProxy.getOrElse(false))
        if(have.isEmpty){
          GroupedResult(elemts.head.fileId, ProxyResult.Unproxied)
        } else if(have.length==wanted.length){
          GroupedResult(elemts.head.fileId, ProxyResult.Proxied)
        } else {
          GroupedResult(elemts.head.fileId, ProxyResult.Partial)
        }
      }
    }

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        if(cache.contains(elem.fileId)){
          cache(elem.fileId) = cache(elem.fileId) ++ scala.collection.mutable.Seq(elem)
          if(cache(elem.fileId).length==expectingProxyCount){ //we have enough replies for this, we can make a judgement
            push(out, makeDecision(cache(elem.fileId)))
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
        println("ProxyResultGroup: pull from downstream")
        pull(in)
      }
    })
  }
}
