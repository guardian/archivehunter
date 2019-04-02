package StreamComponents

import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import models.{FinalCount, GroupedResult}

import scala.concurrent.{Future, Promise}

class GroupedResultCounter extends GraphStageWithMaterializedValue[SinkShape[GroupedResult], Future[FinalCount]] {
  final val in:Inlet[GroupedResult] = Inlet.create("GroupedResultCounter.in")

  override def shape: SinkShape[GroupedResult] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[FinalCount]) = {
    val promise = Promise[FinalCount]()

    val logic = new GraphStageLogic(shape) {
      private var ctr = FinalCount(0,0,0,0)
      private var n = 0

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          //println(s"counter is $n: got $elem")
          if(elem.notNeeded) ctr = ctr.copy(notNeededCount = ctr.notNeededCount+1)
          if(elem.partial) ctr = ctr.copy(partialCount = ctr.partialCount+1)
          if(elem.proxied) ctr = ctr.copy(proxiedCount = ctr.proxiedCount+1)
          if(elem.unProxied) ctr = ctr.copy(unProxiedCount = ctr.unProxiedCount+1)
          n+=1
          println(s"Running total: $n $ctr")
          pull(in)
        }
      })

      override def preStart(): Unit = {
        println("startup: pulling from upstream")
        pull(in)
      }

      override def postStop(): Unit = {
        println("teardown: stream must have finished")
        promise.success(ctr)
      }
    }

    (logic, promise.future)
  }
}
