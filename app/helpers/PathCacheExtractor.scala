package helpers

import akka.stream.scaladsl.{Flow, GraphDSL}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.ArchiveEntry
import models.PathCacheEntry

/**
 * an Akka flow that yields a number of PathCacheEntries for each entry of the path in the given ArchiveEntry
 */
class PathCacheExtractor extends GraphStage[FlowShape[ArchiveEntry, PathCacheEntry]] {
  private final val in:Inlet[ArchiveEntry] = Inlet.create("PathCacheExtractor.in")
  private final val out:Outlet[PathCacheEntry] = Outlet.create("PathCacheExtractor.out")

  override def shape: FlowShape[ArchiveEntry, PathCacheEntry] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    def recursiveGenerateEntries(remainingParts:Seq[String],thisPart:String, level:Int, collection:String, existing:Seq[PathCacheEntry]=Seq()):Seq[PathCacheEntry] = {
      val reconstitutedPath = if(remainingParts.nonEmpty)
        remainingParts.mkString("/") + "/" + thisPart + "/"
      else
        thisPart + "/"

      val reconstitutedParent = if(remainingParts.isEmpty) None else Some(remainingParts.mkString("/"))
      val updatedList = existing :+ PathCacheEntry(
        level,
        key=reconstitutedPath,
        parent=reconstitutedParent,
        collection=collection
      )

      if(remainingParts.nonEmpty){
        recursiveGenerateEntries(remainingParts.init, remainingParts.last, level-1, collection ,updatedList)
      } else {
        updatedList
      }
    }

    private var buffer:Seq[PathCacheEntry] = Seq()
    private var awaitingCompletion = false

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = if(buffer.isEmpty) {
        if(awaitingCompletion) {
          completeStage()
        } else {
          pull(in)
        }
      } else {
        push(out, buffer.head)
        buffer = buffer.tail
      }
    })

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val entry = grab(in)

        val parts = entry.path.split("/").init  //the last element is the filename, which we are not interested in.
        if(parts.isEmpty) {
          pull(in)  //if there is no path for this element then grab the next one
        } else {
          buffer = buffer ++ recursiveGenerateEntries(parts.init, parts.last, parts.length, entry.bucket)
          push(out, buffer.head)
          buffer = buffer.tail
        }
      }

      override def onUpstreamFinish(): Unit = {
        if(buffer.isEmpty) {
          completeStage()
        } else {
          awaitingCompletion = true
        }
      }
    })
  }
}

object PathCacheExtractor {
  def apply() = Flow.fromGraph(GraphDSL.create() { implicit builder=>
    val f = builder.add(new PathCacheExtractor)
    FlowShape(f.in, f.out)
  })
}