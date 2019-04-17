import akka.stream.scaladsl.RunnableGraph
import com.theguardian.multimedia.archivehunter.common.ProblemItemIndexer

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object DockerMain extends MainContent {
  def main(args:Array[String]) : Unit = {
    try {
      println(s"mode is ${sys.env.get("MODE")}")

      sys.env.get("MODE") match {
        case None=> runit(args)
        case Some("stats")=>runit(args)
        case Some("indexfix")=>runFixIndex(args)
        case Some(_)=>throw new RuntimeException("Invalid MODE parameter, you should specify stats or indexfix")
      }
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
    problemsIndexer.newIndex(5,1).map({
      case Failure(err)=>
        println(s"Warning: could not create problem index $problemsIndexName: $err")
      case Success(_)=>
        println(s"Created problem index at $problemsIndexName")
    })

    val problemsSummaryIndexer = new ProblemItemIndexer(s"$problemsIndexName-summary")
    problemsSummaryIndexer.newIndex(1,2).map({
      case Failure(err)=>
        println(s"Warning: Could not create problem summary index $problemsIndexName-summary: $err")
      case Success(_)=>
        println(s"Created problems summary index at $problemsIndexName-summary")
    })

    val forCollection = sys.env.get("FOR_COLLECTION")
    val graphModel = buildGraphModel(forCollection)

    val resultFuture = RunnableGraph.fromGraph(graphModel).run()

    val finalResult = Await.result(resultFuture, 8 hours)
    println(s"Final result is: $finalResult")

    problemsSummaryIndexer.indexSummaryCount(finalResult).map({
      case Right(success)=>
        println(s"Successfully output summary: ${success.body}")
      case Left(err)=>
        println(s"Could not output the summary count: $err")
    })
  }

  def runFixIndex(args:Array[String]) : Unit = {
    Await.ready(runProblemItemFix, 1 hour)
  }
}
