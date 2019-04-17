package StreamComponents

import akka.stream.{Attributes, Inlet, SinkShape}
import akka.stream.stage.{AbstractInHandler, GraphStageLogic, GraphStageWithMaterializedValue}
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.ProblemItemIndexer
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProblemItem

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Success

/**
  * yes, this COULD be more efficient using Elastic4s' stream sink with a delete operation.... but it is only meant for one-off runs so it will
  * do for the time being.
  * @param indexName
  * @param esClient
  */
class ProblemItemDeleteIfEmpty(indexName:String)(implicit esClient:HttpClient) extends GraphStageWithMaterializedValue[SinkShape[ProblemItem], Future[(Int, Int)]] {
  final val in:Inlet[ProblemItem] = Inlet.create("ProblemItemDeleteIfEmpty.in")

  override def shape: SinkShape[ProblemItem] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[(Int, Int)]) = {
    val completionPromise = Promise[(Int, Int)]


    val logic = new GraphStageLogic(shape) {
      var totalCount=0
      var deletedCount=0
      val delayTime=500

      private val problemItemIndexer = new ProblemItemIndexer(indexName)

      def doDelete(elem:ProblemItem, retryNumber:Int=0):Unit = {
        Await.result(problemItemIndexer.deleteEntry(elem), 10 seconds) match {
          case Left(err)=>
            if(err.status==409){  //conflict error; we can try again
              println("ERROR: Conflict error deleting item. Trying again...")
              Thread.sleep(500*(retryNumber+1))
              doDelete(elem, retryNumber+1)
            } else {
              throw new RuntimeException(s"Item delete failed: $err")
            }
          case Right(result)=>
            println(s"DEBUG: ES reports ${result.result.deleted} item(s) deleted")
        }
      }

      setHandler(in, new AbstractInHandler {
        override def onPush(): Unit = {
          val elem = grab(in)

          totalCount+=1
          val resultsWithProblems = elem.verifyResults.filter(result=>result.wantProxy && !result.haveProxy.getOrElse(false))

          if(resultsWithProblems.isEmpty){
            println(s"Removing item $elem")
            doDelete(elem)
            deletedCount+=1
          } else {
            println(s"Item still has ${resultsWithProblems.length} reports, not removing")
          }
          pull(in)
        }
      })

      override def preStart(): Unit = {
        pull(in)
      }

      override def postStop(): Unit = {
        completionPromise.complete(Success((totalCount, deletedCount)))
      }
    }

    (logic, completionPromise.future)
  }
}
