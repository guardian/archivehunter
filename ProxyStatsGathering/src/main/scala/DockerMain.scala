import StreamComponents._
import akka.{Done, NotUsed}
import akka.stream.{ClosedShape, Graph, Outlet}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ProblemItem, ProxyVerifyResult}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProblemItemIndexer, ProxyType}
import models.{GroupedResult, ProxyResult}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object DockerMain extends MainContent {
  def main(args:Array[String]) : Unit = {
    try {
      runit(args)
      System.exit(0)
    } catch {
      case ex:Throwable=>
        ex.printStackTrace()
        System.exit(2)
    }
  }

  def runit(args:Array[String]) : Unit = {
    val indexer = getIndexer(getIndexName)

    val indexName = getIndexName

    val problemsIndexName = getProblemsIndexName

    val problemsIndexer = new ProblemItemIndexer(problemsIndexName)
    problemsIndexer.newIndex(3,1).map({
      case Failure(err)=>
        println(s"Warning: could not create problem index $problemsIndexName: $err")
      case Success(_)=>
        println(s"Created problem index at $problemsIndexName")
    })

    val graphModel = buildGraphModel

    val resultFuture = RunnableGraph.fromGraph(graphModel).run()

    val finalResult = Await.result(resultFuture, 3 hours)
    println(s"Final result is: $finalResult")

    problemsIndexer.indexSummaryCount(finalResult).map({
      case Right(success)=>
        println(s"Successfully output summary: ${success.body}")
      case Left(err)=>
        println(s"Could not output the summary count: $err")
    })
  }
}
