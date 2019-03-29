package StreamComponents

import akka.stream._
import akka.stream.stage._
import com.sksamuel.elastic4s.http.search.SearchHit
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ArchiveEntryHitReader}

class SearchHitToArchiveEntryFlow extends GraphStage[FlowShape[SearchHit,ArchiveEntry]] with ArchiveEntryHitReader {
  final val in:Inlet[SearchHit] = Inlet.create("SearchHitToArchiveEntryFlow.in")
  final val out:Outlet[ArchiveEntry] = Outlet.create("SearchHitToArchiveEntryFlow.out")

  override def shape: FlowShape[SearchHit, ArchiveEntry] = {
    FlowShape.of(in,out)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          ArchiveEntryHR.read(elem) match {
            case Left(err)=>
              println("Could not convert ElasticSearch record to archive entry:", err)
              pull(in)
            case Right(entry)=>
              println(s"Got archive entry for s3://${entry.bucket}/${entry.path}")
              push(out, entry)
          }
        }
      })

      setHandler(out, new AbstractOutHandler {
        override def onPull(): Unit = {
          println("pull from downstream")
          pull(in)
        }
      })
    }
}
