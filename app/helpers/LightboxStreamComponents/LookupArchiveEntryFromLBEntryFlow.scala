package helpers.LightboxStreamComponents

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer}
import com.theguardian.multimedia.archivehunter.common.cmn_models.LightboxEntry
import javax.inject.{Inject, Singleton}
import play.api.Configuration

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * injectable filter stage that looks up the ArchiveEntry (from the index) associated with the given LightboxEntry and
  * returns both as a tuple.
  * You should obtain one like this:
  *
  * class MyControllerOrSomething @Inject() (...., injector:Injector) {
  * .
  * .
  * .
  *   def myFunction() = {
  *   .
  *   .
  *   val lookupFlow = injector.getInstance(classOf[LookupArchiveEntryFromLBEntryFlow])
  *   (now use lookupFlow)
  *   /
  *   /
  *   }
  * }
  * @param config injected Configuration
  * @param esClientManager injected elasticsearch client manager
  */
@Singleton
class LookupArchiveEntryFromLBEntryFlow @Inject()(config:Configuration, esClientManager: ESClientManager) extends GraphStage[FlowShape[LightboxEntry,(ArchiveEntry, LightboxEntry)]]{
  private final val in:Inlet[LightboxEntry] = Inlet("LookupArchiveEntryFromLBEntryFlow.in")
  private final val out:Outlet[(ArchiveEntry, LightboxEntry)] = Outlet("LookupArchiveEntryFromLBEntryFlow.out")

  override def shape: FlowShape[LightboxEntry, (ArchiveEntry, LightboxEntry)] = FlowShape.of(in,out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val indexer = new Indexer(config.get[String]("externalData.indexName"))
    implicit val esClient = esClientManager.getClient()

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val lbEntry = grab(in)

        val archiveEntry = Await.result(indexer.getById(lbEntry.fileId), 30 seconds)
        push(out, (archiveEntry, lbEntry))
      }
    })

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = pull(in)
    })
  }
}
