import StreamComponents._
import akka.{Done, NotUsed}
import akka.stream.{ClosedShape, Graph, Outlet}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, ProxyType}
import models.{FinalCount, GroupedResult, ProxyVerifyResult}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

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

    val converter = new SearchHitToArchiveEntryFlow()
    val mimeTypeBranch = new MimeTypeBranch()
    val mimeTypeWantProxyBranch = new MimeTypeWantProxyBranch()
    val fileTypeWantProxyBranch = new FileTypeWantProxyBranch()

    val counterSink = new GroupedResultCounter

    val graphModel:Graph[ClosedShape, Future[FinalCount]] = GraphDSL.create(counterSink){ implicit builder:GraphDSL.Builder[Future[FinalCount]] => counterSink =>
      import GraphDSL.Implicits._

      val src = builder.add(getStreamSource(indexName))
      val conv = builder.add(converter)
      val mtb = builder.add(mimeTypeBranch)
      val mtwpb = builder.add(mimeTypeWantProxyBranch)
      val ftwpb = builder.add(fileTypeWantProxyBranch)
      //val wantProxyMerge = builder.add(new Merge[ProxyVerifyResult](2, true))
      //val splitter = builder.add(new Broadcast[ProxyVerifyResult](3, true))

      val preVideoMerge = builder.add(new Merge[ProxyVerifyResult](2, false))
      val videoProxyRequest = builder.add(new VerifyProxy(ProxyType.VIDEO, injector))
      val preAudioMerge = builder.add(new Merge[ProxyVerifyResult](2, false))
      val audioProxyRequest = builder.add(new VerifyProxy(ProxyType.AUDIO, injector))
      val preThumbMerge = builder.add(new Merge[ProxyVerifyResult](2, false))
      val thumbProxyRequest = builder.add(new VerifyProxy(ProxyType.THUMBNAIL, injector))

      val postVerifyMerge = builder.add(new Merge[ProxyVerifyResult](3, false))
      val proxyResultGroup = builder.add(new ProxyResultGroup)

      val preCounterMerge = builder.add(new Merge[GroupedResult](3, false))
      //val counter = builder.add(counterSink)

      src ~> conv ~> mtb.in

      //"want proxy" branch

      mtb.out(0) ~> mtwpb
      mtb.out(1) ~> ftwpb

      mtwpb.out(0) ~> preVideoMerge
      ftwpb.out(0) ~> preVideoMerge

      mtwpb.out(1) ~> preAudioMerge
      ftwpb.out(1) ~> preAudioMerge

      mtwpb.out(2) ~> preThumbMerge
      ftwpb.out(2) ~> preThumbMerge

      //ftwpb.out(0) ~> wantProxyMerge.in(1)

      preVideoMerge ~> videoProxyRequest ~> postVerifyMerge.in(0)
      preAudioMerge ~> audioProxyRequest ~> postVerifyMerge.in(1)
      preThumbMerge ~> thumbProxyRequest ~> postVerifyMerge.in(2)

      postVerifyMerge ~> proxyResultGroup ~> preCounterMerge

      //"don't want proxy" branch
      mtwpb.out(3).map(verifyResult=>GroupedResult(verifyResult.fileId,false, false, false, true)).log("mtwbp-none") ~> preCounterMerge
      ftwpb.out(3).map(verifyResult=>GroupedResult(verifyResult.fileId, false, false, false, true)).log("ftwpb-none") ~> preCounterMerge

      //completion
      preCounterMerge ~> counterSink

      ClosedShape
    }

    val resultFuture = RunnableGraph.fromGraph(graphModel).run()

    val finalResult = Await.result(resultFuture, 3 hours)
    println(s"Final result is: $finalResult")
  }
}
