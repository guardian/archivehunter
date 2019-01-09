import java.time.ZonedDateTime
import java.util.UUID

import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification.S3ObjectEntity
import com.amazonaws.services.s3.model.{ObjectMetadata, S3Object}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.sqs
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.google.inject.Guice
import com.theguardian.multimedia.archivehunter.common._
import org.apache.logging.log4j.LogManager
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.{HttpClient, HttpRequestClient}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IngestMessage, JobModelDAO, JobStatus}
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import io.circe.syntax._
import io.circe.generic.auto._

import collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

class InputLambdaMain extends RequestHandler[S3Event, Unit] with DocId with ZonedDateTimeEncoder with StorageClassEncoder {
  private final val logger = LogManager.getLogger(getClass)

  private val injector = Guice.createInjector(new Module)
  val maxRetries = 20

  /**
    * these are extracted out as individual accessors to make over-riding them easier in unit tests
    * @return
    */
  protected def getS3Client = AmazonS3ClientBuilder.defaultClient()
  protected def getElasticClient(clusterEndpoint:String) = {
    val esClient = RestClient.builder(HttpHost.create(clusterEndpoint)).build()
    HttpClient.fromRestClient(esClient)
  }

  protected def getSqsClient() = AmazonSQSClientBuilder.defaultClient()

  protected def getIndexer(indexName: String) = new Indexer(indexName)

  protected def getJobModelDAO = injector.getInstance(classOf[JobModelDAO])

  def sendIngestedMessage(entry:ArchiveEntry) = {
    val client = getSqsClient()
    val taskId = entry.id
    println(s"Ingest has task ID $taskId")
    val msg = IngestMessage(entry, taskId)
    val rq = new SendMessageRequest()
      .withQueueUrl(getNotificationQueue)
      .withMessageBody(msg.asJson.toString())
      .withMessageDeduplicationId(taskId)

    val r = client.sendMessage(rq)
    println(s"Send message with ID ${r.getMessageId}")
  }

  /**
    * returns a user-friendly string representing the event data, for debugging
    * @param event S3Event instance
    */
  def dumpEventData(event: S3Event, indentChar:Option[String]=None) =
    event.getRecords.asScala.foldLeft("")((acc,record)=>acc + s"${indentChar.getOrElse("")}${record.getEventName} on ${record.getEventSource} in ${record.getAwsRegion} at ${record.getEventTime} with ${record.getS3.getObject.getKey}\n")

  def getMetadataWithRetry(bucket:String, key:String, retryNumber:Int=0)(implicit s3Client:AmazonS3):ObjectMetadata = {
    try{
      s3Client.getObjectMetadata(bucket, key)
    } catch {
      case ex:com.amazonaws.services.s3.model.AmazonS3Exception=>
        if(ex.getMessage.contains("Not Found")){
          logger.warn(s"Could not find s3://$bucket/$key on attempt $retryNumber")
          if(retryNumber+1>maxRetries) throw ex
          Thread.sleep(500)
          getMetadataWithRetry(bucket, key, retryNumber+1)
        } else {
          throw ex
        }
      case other:Throwable=>throw other
    }
  }

  /**
    * deal with an item created notification by adding it to the index
    * @param rec S3EventNotification record describing the event
    * @param i implicitly provided [[Indexer]] instance
    * @param s3Client implicitly provided AmazonS3 client instance
    * @param elasticHttpClient implicitly provided HttpClient instance for Elastic Search
    * @return a Future, containing the ID of the new/updated record as a String.  If the operation fails, the Future will fail; pick this up with .onComplete or .recover
    */
  def handleCreated(rec:S3EventNotification.S3EventNotificationRecord)(implicit i:Indexer, s3Client:AmazonS3, elasticHttpClient:HttpClient):Future[String] = {
    val md = getMetadataWithRetry(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey)

    val mimeType = MimeType.fromString(md.getContentType) match {
      case Left(error) =>
        println(s"Could not get MIME type for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey}: $error")
        MimeType("application", "octet-stream")
      case Right(mt) =>
        println(s"MIME type for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} is ${mt.toString}")
        mt
    }

    ArchiveEntry.fromS3(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey).flatMap(entry => {
      println(s"Going to index $entry")
      i.indexSingleItem(entry).map({
        case Success(indexid) =>
          println(s"Document indexed with ID $indexid")
          sendIngestedMessage(entry)
          indexid
        case Failure(exception) =>
          println(s"Could not index document: ${exception.toString}")
          exception.printStackTrace()
          throw exception //fail this future so we enter the recover block below
      })
    })
  }

  /**
    * deal with an item deleted notification by removing it from the index
    * @param rec S3EventNotificationRecord describing the event
    * @param i implictly provided [[Indexer]] instance
    * @param elasticHttpClient implicitly provided HttpClient instance for ElasticSearch
    * @return a Future, containing a summary string if successful. The Future fails if the operation fails.
    */
  def handleRemoved(rec: S3EventNotification.S3EventNotificationRecord)(implicit i:Indexer, elasticHttpClient:HttpClient):Future[String] = {
//    val docId = ArchiveEntry.makeDocId(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey)
//    println(s"Going to remove $docId")
//    i.removeSingleItem(docId)
    ArchiveEntry.fromIndex(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey).flatMap(entry=>{
      println(s"$entry has been removed, updating record to tombstone")
      i.indexSingleItem(entry.copy(beenDeleted = true),Some(entry.id)).map({
        case Success(result)=>result
        case Failure(err)=> throw err
      })
    })
  }

  /**
    * handle an "object restored" message.
    * This is as simple as updating the database to tell the UI that the restore is complete.
    * @param rec S3EventNotification.S3EventNotificationRecord instance
    * @return a Future that completes when the operations have finished. No useful contents.
    */
  def handleRestored(rec:S3EventNotification.S3EventNotificationRecord) = {
    val jobModelDAO = getJobModelDAO
    val docId = makeDocId(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey)

    jobModelDAO.jobsForSource(docId).flatMap(resultList=>{
      val failures = resultList.collect({case Left(err)=>err})
      if(failures.nonEmpty){
        logger.error(s"Could not look up jobs for source ID $docId: ")
        failures.foreach(err=>logger.error(err))
        Future (())
      } else {
        val success = resultList.collect({case Right(result)=>result})
        val restoreJobs = success
          .filter(_.jobType=="RESTORE").filter(_.completedAt.isEmpty)
        logger.info(s"Found restore jobs: $restoreJobs")

        val updateFutures = restoreJobs.map(job=>{
          val updatedJob = job.copy(completedAt = Some(ZonedDateTime.now()), jobStatus = JobStatus.ST_SUCCESS)
          jobModelDAO.putJob(updatedJob)
        })

        Future.sequence(updateFutures).map(updateResults=>{
          val updateFailures = updateResults.collect({case Some(Left(err))=>err})
          if(updateFailures.nonEmpty){
            logger.error(s"Could not update jobs for source ID $docId: ")
            updateFailures.foreach(err=>logger.error(err))
            ()
          } else {
            logger.info(s"Updated jobs for source ID $docId")
            ()
          }
        })
      }
    })
  }

  protected def getNotificationQueue = sys.env.get("NOTIFICATION_QUEUE") match {
    case Some(name)=>name
    case None=>
      Option(System.getProperty("NOTIFICATION_QUEUE")) match {
        case Some(name)=>name
        case None=>
          throw new RuntimeException("You must specify NOTIFICATION_QUEUE in the environment")
      }
  }

  protected def getIndexName = sys.env.get("INDEX_NAME") match {
    case Some(name)=>name
    case None=>
      Option(System.getProperty("INDEX_NAME")) match {
        case Some(name)=>name
        case None=>throw new RuntimeException("You must specify an INDEX_NAME in the environment")
      }
  }

  protected def getClusterEndpoint = sys.env.get("ELASTICSEARCH") match {
    case Some(name)=>name
    case None=>
      Option(System.getProperty("ELASTICSEARCH")) match {
        case Some(name) => name
        case None =>
          throw new RuntimeException("You must specify an Elastic Search cluster endpoint in ELASTICSEARCH in the environment")
      }
  }

  override def handleRequest(event:S3Event, context:Context): Unit = {
    val indexName = getIndexName

    val clusterEndpoint = getClusterEndpoint

    implicit val s3Client:AmazonS3 = getS3Client
    implicit val elasticClient:HttpClient = getElasticClient(clusterEndpoint)
    implicit val i:Indexer = getIndexer(indexName)

    println(s"Lambda was triggered with: \n${dumpEventData(event, Some("\t"))}")
    val resultList = event.getRecords.asScala.map(rec=>{

      println(s"Source object is s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} in ${rec.getAwsRegion}")
      println(s"Event was sent by ${rec.getUserIdentity.getPrincipalId}")

      rec.getEventName match {
        case "ObjectCreated:Put"=>
          handleCreated(rec)
        case "ObjectCreated:CompleteMultipartUpload"=>
          handleCreated(rec)
        case "ObjectRemoved:Delete"=>
          handleRemoved(rec)
        case "ObjectRestore:Completed"=>
          handleRestored(rec)
        case other:String=>
          println(s"ERROR: received unknown event $other")
          throw new RuntimeException(s"unknown event $other received")
      }
    })

    //need to block here, AWS terminates the lambda as soon as the function returns.
    //as a bonus, if any of the operations fail this raises an exception and therefore is reported as a run failure
    Await.result(Future.sequence(resultList), 45 seconds)
  }
}
