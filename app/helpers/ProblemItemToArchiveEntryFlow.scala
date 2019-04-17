package helpers

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProblemItem
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ProblemItemToArchiveEntryFlow @Inject()(config:Configuration, esClientManager:ESClientManager) extends GraphStage[FlowShape[ProblemItem,ArchiveEntry]]{
  final val in:Inlet[ProblemItem] = Inlet.create("ProblemItemToArchiveEntryFlow.in")
  final val out:Outlet[ArchiveEntry] = Outlet.create("ProblemItemToArchiveEntryFlow.out")

  override def shape: FlowShape[ProblemItem, ArchiveEntry] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val logger = Logger(getClass)
    val indexName = config.getOptional[String]("externalData.indexName").getOrElse("archivehunter")

    private implicit val esClient = esClientManager.getClient()

    private val indexer = new Indexer(indexName)

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        val entry = Await.result(indexer.getById(elem.fileId), 10 seconds)
        push(out, entry)
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}
