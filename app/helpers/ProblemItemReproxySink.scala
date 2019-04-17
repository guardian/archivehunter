package helpers

import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import com.theguardian.multimedia.archivehunter.common.{ProxyLocationDAO, ProxyType}
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.{ProxyGenerators, RequestType}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProblemItem
import javax.inject.Inject
import play.api.Logger

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

class ProblemItemReproxySink @Inject() (proxyGenerators: ProxyGenerators)(implicit proxyLocationDAO:ProxyLocationDAO)  extends GraphStageWithMaterializedValue[SinkShape[ProblemItem], Future[Int]] {
  final val in:Inlet[ProblemItem] = Inlet.create("ProblemItemReproxySink.in")

  override def shape: SinkShape[ProblemItem] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Int]) = {
    val completionPromise = Promise[Int]()

    val logic = new GraphStageLogic(shape) {
      private val logger = Logger(getClass)
      var ctr=0

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          val futureList = elem.verifyResults.map(verifyResult=>{
            if(verifyResult.wantProxy && !verifyResult.haveProxy.getOrElse(false)){
              logger.info(s"Need ${verifyResult.proxyType.toString} proxy for $elem")

              val requestType = verifyResult.proxyType match {
                case ProxyType.VIDEO=> (RequestType.PROXY, Some(ProxyType.VIDEO))
                case ProxyType.AUDIO=> (RequestType.PROXY, Some(ProxyType.AUDIO))
                case ProxyType.THUMBNAIL=> (RequestType.THUMBNAIL, None)
              }
              Some(proxyGenerators.requestProxyJob(requestType._1, elem.fileId, requestType._2))
            } else {
              logger.info(s"Don't need ${verifyResult.proxyType.toString} proxy for $elem")
              None
            }
          })

          val results = Await.result(Future.sequence(futureList.collect({case Some(fut)=>fut})), 10 seconds)
          val total = results.length
          val failures = results.collect({case Failure(err)=>err})
          if(failures.nonEmpty){
            logger.warn(s"${failures.length}/$total proxy requests failed")
            failures.foreach(err=>logger.error("Proxy request failed: ", err))
          }

          ctr+=1

          pull(in)
        }
      })

      override def preStart(): Unit =
      {
        pull(in)
      }

      override def postStop(): Unit = {
        completionPromise.complete(Success(ctr))
      }
    }

    (logic, completionPromise.future)
  }
}
