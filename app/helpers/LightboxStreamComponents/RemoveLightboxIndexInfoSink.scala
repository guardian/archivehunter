package helpers.LightboxStreamComponents

import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import org.slf4j.MDC
import play.api.Logger

import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class RemoveLightboxIndexInfoSink (userEmail:String)(implicit esClient:HttpClient, indexer:Indexer) extends GraphStageWithMaterializedValue[SinkShape[ArchiveEntry], Future[Int]]{
  private final val in = Inlet[ArchiveEntry]("RemoveLightboxIndexInfoSink.in")

  override def shape: SinkShape[ArchiveEntry] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Int]) = {
    val promise = Promise[Int]()
    var ctr:Int = 0

    val logic = new GraphStageLogic(shape) {
      private val logger = Logger(getClass)

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          val updatedElem = elem.copy(lightboxEntries = elem.lightboxEntries.filter(_.owner!=userEmail))
          //TODO: this could be improved by converting this to a Flow and then using an existing Elastic4s bulk consumer to write the index in bulk.
          Await.ready(indexer.indexSingleItem(updatedElem).map({
            case Right(result)=>
              logger.info(s"Removed lightbox entry data from $result")
              ctr+=1
            case Left(err)=>
              MDC.put("error",err.toString)
              MDC.put("entry",elem.toString)
              logger.error(s"Could not remove lightbox entry data: ${err.toString}")
              throw new RuntimeException(err.toString)
          }), 30 seconds)
          pull(in)
        }
      })

      override def preStart(): Unit = pull(in)

      override def postStop(): Unit = promise.success(ctr)
    }

    (logic, promise.future)
  }
}
