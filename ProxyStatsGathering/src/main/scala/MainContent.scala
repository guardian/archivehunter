import StreamComponents._
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape, Graph, Materializer}
import akka.stream.scaladsl.{Broadcast, GraphDSL, Merge, Sink, Source}
import com.google.inject.Guice
import com.theguardian.multimedia.archivehunter.common.{Indexer, ProblemItemRequestBuilder, ProxyLocationDAO, ProxyType}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, ESClientManagerImpl}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ProblemItemCount, ProblemItem, ProxyVerifyResult}
import models.{GroupedResult, ProxyResult}

import scala.concurrent.Future

trait MainContent extends ProblemItemRequestBuilder{
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._

  implicit val system:ActorSystem = ActorSystem("root")
  implicit val mat:Materializer = ActorMaterializer.create(system)

  protected val injector = Guice.createInjector(new Module(system))

  protected val writeBatchSize=100

  protected def getIndexName = sys.env.get("INDEX_NAME") match {
    case Some(name)=>name
    case None=>
      Option(System.getProperty("INDEX_NAME")) match {
        case Some(name)=>name
        case None=>throw new RuntimeException("You must specify an INDEX_NAME in the environment")
      }
  }

  protected def getProblemsIndexName = sys.env.get("PROBLEMS_INDEX_NAME") match {
    case Some(name)=>name
    case None=>
      Option(System.getProperty("PROBLEMS_INDEX_NAME")) match {
        case Some(name)=>name
        case None=>throw new RuntimeException("You must specify a PROBLEMS_INDEX_NAME in the environment")
      }
  }

  override val problemsIndexName: String = getProblemsIndexName

  protected def getIndexer(indexName: String) = new Indexer(indexName)

  protected def getProxyLocationDAO = injector.getInstance(classOf[ProxyLocationDAO])

  private val esClientMgr = try {
    injector.getInstance(classOf[ESClientManager])
  } catch {
    case ex:Throwable=>
      println(s"ERROR: $ex")
      System.exit(1)
      //this line should not actually be called, but is here so that the compiler stays ok
      new ESClientManagerImpl(null)
  }

  protected implicit val esClient = esClientMgr.getClient()

  def getStreamSource(indexName:String) = {
    //pull back the entire catalogue.  We'll check it one at a time.
    Source.fromPublisher(esClient.publisher(search(indexName) scroll "5m"))
  }

  def getProblemElementsSink(indexName:String) = {
    Sink.fromSubscriber(esClient.subscriber[ProblemItem](writeBatchSize, concurrentRequests=3))
  }

  def buildGraphModel = {
    val converter = new SearchHitToArchiveEntryFlow()
    val mimeTypeBranch = new MimeTypeBranch()
    val mimeTypeWantProxyBranch = new MimeTypeWantProxyBranch()
    val fileTypeWantProxyBranch = new FileTypeWantProxyBranch()

    val counterSink = new GroupedResultCounter

    GraphDSL.create(counterSink){ implicit builder:GraphDSL.Builder[Future[ProblemItemCount]] =>counterSink =>
      import GraphDSL.Implicits._

      val src = builder.add(getStreamSource(getIndexName))
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
  }

}
