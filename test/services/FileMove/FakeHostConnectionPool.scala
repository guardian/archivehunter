package services.FileMove

import akka.NotUsed
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, GraphDSL}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic, GraphStageWithMaterializedValue}
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import services.FileMove.FakeHostConnectionPool.RequestValidatorFunc

import scala.util.Try

object FakeHostConnectionPool {
  type RequestValidatorFunc[T] = (Int, HttpRequest, T)=>Try[HttpResponse]

  def apply[T](validator:RequestValidatorFunc[T]) = Flow.fromGraph({
    val f = new FakeHostConnectionPool[T](validator)
    GraphDSL.createGraph(f) { implicit builder=> p=>
      FlowShape.of(p.in, p.out)
    }
  })
}

/**
  * "Mock" for HostConnectionPool that
  * @param validator
  * @tparam T
  */
class FakeHostConnectionPool[T](validator:RequestValidatorFunc[T]) extends GraphStageWithMaterializedValue[FlowShape[(HttpRequest, T), (Try[HttpResponse], T)], Http.HostConnectionPool]
{
  private final val logger = LoggerFactory.getLogger(getClass)
  private final val in = Inlet.create[(HttpRequest, T)]("FakeHostConnectionPool.in")
  private final val out = Outlet.create[(Try[HttpResponse], T)]("FakeHostConnectionPool.out")

  override def shape: FlowShape[(HttpRequest, T), (Try[HttpResponse], T)] = FlowShape.of(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = (
    new GraphStageLogic(shape) {
      private final var ctr:Int=0
      setHandler(out, new AbstractOutHandler {
        override def onPull(): Unit = pull(in)
      })

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          val response = validator(ctr, elem._1, elem._2)
          push(out, (response, elem._2))
          ctr+=1
        }
      })
    },
    Mockito.mock(classOf[Http.HostConnectionPool])
  )
}
