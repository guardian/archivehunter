import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType}
import org.apache.logging.log4j.LogManager
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.{HttpClient, HttpRequestClient}
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient

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

    println(s"Lambda was triggered with $event")
    event.getRecords.forEach(rec=>{
      val s3ObjectRef = rec.getS3.getObject

      val md = s3Client.getObjectMetadata(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey)

      println(s"Source object is s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} in ${rec.getAwsRegion}")
      println(s"Event was sent by ${rec.getUserIdentity}")

      val mimeType = MimeType.fromString(md.getContentType) match {
        case Left(error)=>
          logger.warn(s"Could not get MIME type for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey}: $error")
          MimeType("application","octet-stream")
        case Right(mt)=>
          logger.debug(s"MIME type for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} is ${mt.toString}")
          mt
      }

      ArchiveEntry.fromS3(rec.getS3.getBucket.getName, s3ObjectRef.getKey).map({
        case Success(entry)=>i.indexSingleItem(entry)
        case Failure(error)=>logger.error(s"Could not look up metadata for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} in ${rec.getAwsRegion}", error)
      }).recover({
        case ex:Throwable=>
          logger.error(s"Unable to index ${rec.toString}: ", ex)
          throw ex
      })
    })
  }
}
