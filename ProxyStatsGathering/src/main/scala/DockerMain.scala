import StreamComponents._
import akka.{Done, NotUsed}
import akka.stream.{ClosedShape, Graph, Outlet}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ProblemItem, ProxyVerifyResult}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProblemItemIndexer, ProxyType}
import models.{FinalCount, GroupedResult, ProxyResult}

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

    val converter = new SearchHitToArchiveEntryFlow()
    val mimeTypeBranch = new MimeTypeBranch()
    val mimeTypeWantProxyBranch = new MimeTypeWantProxyBranch()
    val fileTypeWantProxyBranch = new FileTypeWantProxyBranch()

    val counterSink = new GroupedResultCounter

    val problemsIndexer = new ProblemItemIndexer(problemsIndexName)
    problemsIndexer.newIndex(3,1).map({
      case Failure(err)=>
        println(s"Warning: could not create problem index $problemsIndexName: $err")
      case Success(_)=>
        println(s"Created problem index at $problemsIndexName")
    })

    val graphModel:Graph[ClosedShape, Future[FinalCount]] = GraphDSL.create(counterSink){ implicit builder:GraphDSL.Builder[Future[FinalCount]] => counterSink =>
      import GraphDSL.Implicits._

      val src = builder.add(getStreamSource(indexName))
      val conv = builder.add(converter)
      val mtb = builder.add(mimeTypeBranch)
      val mtwpb = builder.add(mimeTypeWantProxyBranch)
      val ftwpb = builder.add(fileTypeWantProxyBranch)

      val isDotFileBranch = builder.add(new IsDotFileBranch)
      val isGlacierBranch = builder.add(injector.getInstance(classOf[IsGlacierBranch]))
      val preVideoMerge = builder.add(new Merge[ProxyVerifyResult](2, false))
      val videoProxyRequest = builder.add(new VerifyProxy(ProxyType.VIDEO, injector))
      val preAudioMerge = builder.add(new Merge[ProxyVerifyResult](2, false))
      val audioProxyRequest = builder.add(new VerifyProxy(ProxyType.AUDIO, injector))
      val preThumbMerge = builder.add(new Merge[ProxyVerifyResult](2, false))
      val thumbProxyRequest = builder.add(new VerifyProxy(ProxyType.THUMBNAIL, injector))

      val postVerifyMerge = builder.add(new Merge[ProxyVerifyResult](3, false))
      val proxyResultGroup = builder.add(new ProxyResultGroup)
      val groupCounter = builder.add(new GroupCounter)

      val postGroupBroadcast = builder.add(new Broadcast[Seq[ProxyVerifyResult]](2,true))

      val preCounterMerge = builder.add(new Merge[GroupedResult](5, false))

      val recordProblemSink = builder.add(getProblemElementsSink(problemsIndexName))

      src ~> conv ~> isDotFileBranch
      isDotFileBranch.out(0).map(entry=>GroupedResult(entry.id, ProxyResult.DotFile)) ~> preCounterMerge
      isDotFileBranch.out(1) ~> isGlacierBranch

      isGlacierBranch.out(0).map(entry=>GroupedResult(entry.id, ProxyResult.GlacierClass)) ~> preCounterMerge
      isGlacierBranch.out(1) ~> mtb

      //"want proxy" branch
      mtb.out(0) ~> mtwpb.in
      mtb.out(1) ~> ftwpb.in

      mtwpb.out(0) ~> preVideoMerge
      ftwpb.out(0) ~> preVideoMerge

      mtwpb.out(1) ~> preAudioMerge
      ftwpb.out(1) ~> preAudioMerge

      mtwpb.out(2) ~> preThumbMerge
      ftwpb.out(2) ~> preThumbMerge


      preVideoMerge ~> videoProxyRequest ~> postVerifyMerge.in(0)
      preAudioMerge ~> audioProxyRequest ~> postVerifyMerge.in(1)
      preThumbMerge ~> thumbProxyRequest ~> postVerifyMerge.in(2)

      postVerifyMerge ~> proxyResultGroup ~> postGroupBroadcast

      //once we have proxy results grouped, broadcast the results between a counter branch and a log-to-elastic branch
      postGroupBroadcast.out(0) ~> groupCounter ~> preCounterMerge
      postGroupBroadcast.out(1).map(resultSeq=>ProblemItem(resultSeq.head.fileId, resultSeq)) ~> recordProblemSink

      //"don't want proxy" branch
      mtwpb.out(3).map(verifyResult=>GroupedResult(verifyResult.fileId, ProxyResult.NotNeeded)).log("mtwbp-none") ~> preCounterMerge
      ftwpb.out(3).map(verifyResult=>GroupedResult(verifyResult.fileId, ProxyResult.NotNeeded)).log("ftwpb-none") ~> preCounterMerge

      //completion
      preCounterMerge ~> counterSink

      ClosedShape
    }

    val resultFuture = RunnableGraph.fromGraph(graphModel).run()

    val finalResult = Await.result(resultFuture, 3 hours)
    println(s"Final result is: $finalResult")
  }
}
