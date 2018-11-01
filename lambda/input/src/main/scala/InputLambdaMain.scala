import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification.S3ObjectEntity
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType}
import org.apache.logging.log4j.LogManager
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.{HttpClient, HttpRequestClient}
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient

import collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
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

  /**
    * deal with an item created notification by adding it to the index
    * @param rec
    * @param s3ObjectRef
    * @param i
    * @param s3Client
    * @param elasticHttpClient
    * @return
    */
  def handleCreated(rec:S3EventNotification.S3EventNotificationRecord, s3ObjectRef:S3ObjectEntity)(implicit i:Indexer, s3Client:AmazonS3, elasticHttpClient:HttpClient):Future[String] =
    ArchiveEntry.fromS3(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey).flatMap(entry=>{
      println(s"Going to index $entry")
      i.indexSingleItem(entry)
    }).map({
      case Success(indexid)=>
        println(s"Document indexed with ID $indexid")
        indexid
      case Failure(exception)=>
        println(s"Could not index document: ${exception.toString}")
        exception.printStackTrace()
        throw exception //fail this future so we enter the recover block below
    })

  /**
    * deal with an item deleted notification by removing it from the index
    * @param rec
    * @param i
    * @param elasticHttpClient
    * @return
    */
  def handleRemoved(rec: S3EventNotification.S3EventNotificationRecord)(implicit i:Indexer, elasticHttpClient:HttpClient):Future[String] = {
    i.removeSingleItem(ArchiveEntry.makeDocId(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey))
  }

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
    implicit val i = getIndexer(indexName)

    println(s"Lambda was triggered with: \n${dumpEventData(event, Some("\t"))}")
    val resultList = event.getRecords.asScala.map(rec=>{
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

      rec.getEventName match {
        case "ObjectCreated:Put"=>
          handleCreated(rec,s3ObjectRef)
        case "ObjectRemoved:Delete"=>
          handleRemoved(rec)
        case other:String=>
          println(s"ERROR: received unknown event $other")
          throw new RuntimeException(s"unknown event $other received")
      }

    })

    Await.result(Future.sequence(resultList), 45 seconds)
  }
}
