package StreamComponents

import java.time.ZonedDateTime

import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProblemItemCount
import models.{GroupedResult, ProxyResult}

import scala.annotation.switch
import scala.concurrent.{Future, Promise}

class GroupedResultCounter extends GraphStageWithMaterializedValue[SinkShape[GroupedResult], Future[ProblemItemCount]] {
  final val in:Inlet[GroupedResult] = Inlet.create("GroupedResultCounter.in")

  override def shape: SinkShape[GroupedResult] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[ProblemItemCount]) = {
    val promise = Promise[ProblemItemCount]()

    val logic = new GraphStageLogic(shape) {
      private var ctr = ProblemItemCount(ZonedDateTime.now(), None, 0,0,0,0,0,0)
      private var n = 0

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          //println(s"counter is $n: got $elem")
          (elem.result: @switch) match
          {
            case ProxyResult.NotNeeded=> ctr.copy(notNeededCount = ctr.notNeededCount + 1)
            case ProxyResult.Partial=> ctr = ctr.copy(partialCount = ctr.partialCount + 1)
            case ProxyResult.Proxied=> ctr = ctr.copy(proxiedCount = ctr.proxiedCount + 1)
            case ProxyResult.Unproxied=> ctr = ctr.copy(unProxiedCount = ctr.unProxiedCount + 1)
            case ProxyResult.DotFile=> ctr = ctr.copy(dotFile = ctr.dotFile + 1)
            case ProxyResult.GlacierClass=> ctr = ctr.copy(glacier = ctr.glacier + 1)
          }
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
        promise.success(ctr.copy(scanFinish = Some(ZonedDateTime.now())))
      }
    }

    (logic, promise.future)
  }
}
