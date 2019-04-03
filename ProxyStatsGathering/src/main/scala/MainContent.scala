import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.Guice
import com.theguardian.multimedia.archivehunter.common.{Indexer, ProblemItemRequestBuilder, ProxyLocationDAO}
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, ESClientManagerImpl}
import com.theguardian.multimedia.archivehunter.common.cmn_models.ProblemItem

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
}
