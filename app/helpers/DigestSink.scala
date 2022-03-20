package helpers

import akka.stream.scaladsl.{GraphDSL, Sink}
import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import akka.util.ByteString

import java.security.MessageDigest
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object DigestSink {
  def apply(algorithm:String) = {
    val factory = new DigestSink(algorithm)

    Sink.fromGraph(GraphDSL.createGraph(factory) { implicit builder=> s=>
      SinkShape.of(s.in)
    })
  }
}
/**
  * Simple sink to perform a checksum on all the incoming data and return it at the stream end.
  * @param algorithm algorithm to checksum.  Can by anything supported by MessageDigest.getInstance.
  */
class DigestSink(algorithm:String) extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[ByteString]]{
  private final val in:Inlet[ByteString] = Inlet.create("DigestSink.in")

  override def shape: SinkShape[ByteString] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[ByteString]) = {
    val completionPromise:Promise[ByteString] = Promise()
    val digester = MessageDigest.getInstance(algorithm)

    val logic = new GraphStageLogic(shape) {
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val nextBlock = grab(in)
          digester.update(nextBlock.asByteBuffer)
          pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          val result = ByteString(digester.digest())
          completionPromise.complete(Success(result))
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          completionPromise.complete(Failure(ex))
        }
      })

      override def preStart(): Unit = {
        pull(in)
      }
    }

    (logic, completionPromise.future)
  }
}
