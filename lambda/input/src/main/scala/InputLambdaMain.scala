import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType}
import org.apache.logging.log4j.LogManager
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.{HttpClient, HttpRequestClient}
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import collection.JavaConverters._
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

class InputLambdaMain extends RequestHandler[S3Event, Unit] {
  private final val logger = LogManager.getLogger(getClass)

  /**
    * these are extracted out as individual accessors to make over-riding them easier in unit tests
    * @return
    */
  protected def getS3Client = AmazonS3ClientBuilder.defaultClient()
  protected def getElasticClient(clusterEndpoint:String) = {
    val esClient = RestClient.builder(HttpHost.create(clusterEndpoint)).build()
    HttpClient.fromRestClient(esClient)
  }

  protected def getIndexer(indexName: String) = new Indexer(indexName)

  /**
    * returns a user-friendly string representing the event data, for debugging
    * @param event S3Event instance
    */
  def dumpEventData(event: S3Event, indentChar:Option[String]=None) =
    event.getRecords.asScala.foldLeft("")((acc,record)=>acc + s"${indentChar.getOrElse("")}${record.getEventName} on ${record.getEventSource} in ${record.getAwsRegion} at ${record.getEventTime} with ${record.getS3.getObject.getKey}\n")

  override def handleRequest(event:S3Event, context:Context): Unit = {
    val indexName = sys.env.get("INDEX_NAME") match {
      case Some(name)=>name
      case None=>
        Option(System.getProperty("INDEX_NAME")) match {
          case Some(name)=>name
          case None=>throw new RuntimeException("You must specify an INDEX_NAME in the environment")
        }
    }

    val clusterEndpoint = sys.env.get("ELASTICSEARCH") match {
      case Some(name)=>name
      case None=>
        Option(System.getProperty("ELASTICSEARCH")) match {
          case Some(name) => name
          case None =>
            throw new RuntimeException("You must specify an Elastic Search cluster endpoint in ELASTICSEARCH in the environment")
        }
    }

    implicit val s3Client:AmazonS3 = getS3Client
    implicit val elasticClient:HttpClient = getElasticClient(clusterEndpoint)
    val i = getIndexer(indexName)

    println(s"Lambda was triggered with: \n${dumpEventData(event, Some("\t"))}")
    event.getRecords.forEach(rec=>{
      val s3ObjectRef = rec.getS3.getObject

      val md = s3Client.getObjectMetadata(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey)

      println(s"Source object is s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} in ${rec.getAwsRegion}")
      println(s"Event was sent by ${rec.getUserIdentity.getPrincipalId}")

      val mimeType = MimeType.fromString(md.getContentType) match {
        case Left(error)=>
          println(s"Could not get MIME type for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey}: $error")
          MimeType("application","octet-stream")
        case Right(mt)=>
          println(s"MIME type for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} is ${mt.toString}")
          mt
      }

      ArchiveEntry.fromS3(rec.getS3.getBucket.getName, s3ObjectRef.getKey).map(entry=>{
          println(s"Going to index $entry")
          i.indexSingleItem(entry).map({
            case Success(indexid)=>
              println(s"Document indexed with ID $indexid")
            case Failure(exception)=>
              println(s"Could not index document: ${exception.toString}")
              exception.printStackTrace()
              throw exception //fail this future so we enter the recover block below
          })
      }).recover({
        case ex:Throwable=>
          println(s"Unable to index ${rec.toString}: ", ex)
          throw ex
      })
    })
  }
}
