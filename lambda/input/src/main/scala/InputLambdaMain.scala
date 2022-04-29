import java.net.URLDecoder
import java.time.ZonedDateTime
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.google.inject.Guice
import com.theguardian.multimedia.archivehunter.common._
import org.apache.logging.log4j.LogManager
import com.sksamuel.elastic4s.http.ElasticClient
import com.sksamuel.elastic4s.http.ElasticDsl.update
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.PathCacheExtractor
import com.theguardian.multimedia.archivehunter.common.cmn_models.{IngestMessage, ItemNotFound, JobModelDAO, JobStatus, LightboxEntryDAO, PathCacheEntry, PathCacheIndexer, RestoreStatus}
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import io.circe.syntax._
import io.circe.generic.auto._
import software.amazon.awssdk.services.s3.S3Client

import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.S3ClientExtensions._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{HeadObjectResponse, NoSuchKeyException}
import software.amazon.awssdk.http.apache.ApacheHttpClient
import akka.actor.ActorSystem
import akka.stream.Materializer

class InputLambdaMain (implicit actorSystem:ActorSystem, mat:Materializer) extends RequestHandler[S3Event, Unit] with DocId with ZonedDateTimeEncoder with StorageClassEncoder {
  private final val logger = LogManager.getLogger(getClass)

  private val injector = Guice.createInjector(new Module(actorSystem, mat))
  val maxRetries = 20

  def getRegionFromEnvironment:Option[Region] = Option(System.getenv("AWS_REGION")).map(Region.of)

  /**
    * these are extracted out as individual accessors to make over-riding them easier in unit tests
    * @return
    */
  protected def getS3Client = S3Client.builder().httpClientBuilder(ApacheHttpClient.builder()).build()
  protected def getElasticClient(clusterEndpoint:String) = {
    val esClient = RestClient.builder(HttpHost.create(clusterEndpoint)).build()
    ElasticClient.fromRestClient(esClient)
  }

  protected def getSqsClient() = AmazonSQSClientBuilder.defaultClient()

  protected def getIndexer(indexName: String) = new Indexer(indexName)

  protected def getPathCacheIndexer(indexName: String, elasticClient:ElasticClient) = new PathCacheIndexer(indexName, elasticClient)

  protected def getJobModelDAO = injector.getInstance(classOf[JobModelDAO])

  protected def getLightboxEntryDAO = injector.getInstance(classOf[LightboxEntryDAO])

  def sendIngestedMessage(entry:ArchiveEntry) = {
    val client = getSqsClient()
    val taskId = entry.id
    println(s"Ingest has task ID $taskId")
    val msg = IngestMessage(entry, taskId)
    val rq = new SendMessageRequest()
      .withQueueUrl(getNotificationQueue)
      .withMessageBody(msg.asJson.toString())

    val r = client.sendMessage(rq)
    println(s"Send message with ID ${r.getMessageId}")
  }

  /**
    * returns a user-friendly string representing the event data, for debugging
    * @param event S3Event instance
    */
  def dumpEventData(event: S3Event, indentChar:Option[String]=None) =
    event.getRecords.asScala.foldLeft("")((acc,record)=>acc + s"${indentChar.getOrElse("")}${record.getEventName} on ${record.getEventSource} in ${record.getAwsRegion} at ${record.getEventTime} with ${URLDecoder.decode(record.getS3.getObject.getKey,"UTF-8")}\n")

  def getMetadataWithRetry(bucket:String, key:String, maybeVersion:Option[String], retryNumber:Int=0)(implicit s3Client:S3Client):Try[HeadObjectResponse] = {
      s3Client.getObjectMetadata(bucket, key,maybeVersion) match {
      case Failure(ex:NoSuchKeyException)=>
        logger.warn(s"Could not find s3://$bucket/$key on attempt $retryNumber")
        if(retryNumber+1>maxRetries) {
          Failure(ex)
        } else {
          Thread.sleep(500)
          getMetadataWithRetry(bucket, key, maybeVersion, retryNumber + 1)
        }
      case err@Failure(_)=>err
      case ok@Success(_)=>ok
    }
  }

  def writePathCacheEntries(newCacheEntries:Seq[PathCacheEntry])
                           (implicit pathCacheIndexer:PathCacheIndexer,  elasticClient:ElasticClient) = {
    import com.sksamuel.elastic4s.http.ElasticDsl._
    import io.circe.generic.auto._
    import com.sksamuel.elastic4s.circe._

    Future.sequence(
      newCacheEntries.map(entry=>elasticClient.execute(
        update(entry.collection + entry.key) in s"${pathCacheIndexer.indexName}/pathcache" docAsUpsert entry
      ))
    ).map(responses=>{
      val failures = responses.filter(_.isError)
      if(failures.nonEmpty) {
        logger.error(s"${failures.length} path cache entries failed: ")
        failures.foreach(err=>logger.error(err.error.reason))
      }
      println(s"${failures.length} / ${newCacheEntries.length} path cache entries failed")
    })
  }

  /**
    * deal with an item created notification by adding it to the index
    * @param rec S3EventNotification record describing the event
    * @param i implicitly provided [[Indexer]] instance
    * @param s3Client implicitly provided AmazonS3 client instance
    * @param elasticElasticClient implicitly provided ElasticClient instance for Elastic Search
    * @return a Future, containing the ID of the new/updated record as a String.  If the operation fails, the Future will fail; pick this up with .onComplete or .recover
    */
  def handleCreated(rec:S3EventNotification.S3EventNotificationRecord,path: String)(implicit i:Indexer, pathCacheIndexer:PathCacheIndexer, s3Client:S3Client, elasticElasticClient:ElasticClient):Future[String] = {
    //build a list of entries to add to the path cache
    val pathParts = path.split("/").init  //the last element is the filename, which we are not interested in.

    val newCacheEntries = if(pathParts.isEmpty) {
      Seq()
    } else {
      PathCacheExtractor.recursiveGenerateEntries(pathParts.init, pathParts.last, pathParts.length, rec.getS3.getBucket.getName)
    }

    println(s"going to update ${newCacheEntries.length} path cache entries")
    writePathCacheEntries(newCacheEntries).flatMap(_=> {
      println(s"Got bucket ${rec.getS3.getBucket.getName}")
      println(s"Got path $path")
      println(s"Got version ID ${Option(rec.getS3.getObject.getVersionId)}")
      println(s"Got region ${getRegionFromEnvironment.get.toString}")
      ArchiveEntry.fromS3(rec.getS3.getBucket.getName, path, Option(rec.getS3.getObject.getVersionId), getRegionFromEnvironment.get.toString).flatMap(entry => {
        println(s"Going to index $entry")
        i.indexSingleItem(entry).flatMap({
          case Right(indexid) =>
            println(s"Document indexed with ID $indexid")
            sendIngestedMessage(entry)
            i.getById(indexid).map(entry=>{
              println(s"DEBUGGING - read-back of entry $indexid gives $entry")
              indexid
            })
          case Left(err) =>
            println(s"Could not index document: ${err.toString}")
            Future.failed(new RuntimeException(err.toString)) //fail this future so we enter the recover block below
        })
      })
    })
  }

  /**
    * deal with an item deleted notification by removing it from the index.  If the item does not exist, don't treat it
    * as a failure, simply note in the log.
    * @param rec S3EventNotificationRecord describing the event
    * @param i implictly provided [[Indexer]] instance
    * @param elasticElasticClient implicitly provided ElasticClient instance for ElasticSearch
    * @return a Future, containing a summary string if successful. The Future fails if the operation fails.
    */
  def handleRemoved(rec: S3EventNotification.S3EventNotificationRecord,path: String)(implicit i:Indexer, elasticElasticClient:ElasticClient):Future[String] = {
    ArchiveEntry.fromIndexFull(rec.getS3.getBucket.getName, path).flatMap({
      case Right(entry)=>
        println(s"$entry has been removed, updating record to tombstone")
        i.indexSingleItem(entry.copy(beenDeleted = true),Some(entry.id)).map({
          case Right(result)=>result
          case Left(err)=> throw new RuntimeException(err.toString)
        })
      case Left(ItemNotFound(docId))=>
        val msg = s"$docId did not exist in the index, returning"
        println(msg)
        Future(msg)
      case Left(other)=>
        throw new RuntimeException(other.toString)
    })
  }

  /**
    * handle an "object restored" message.
    * This is as simple as updating the database to tell the UI that the restore is complete.
    * @param rec S3EventNotification.S3EventNotificationRecord instance
    * @return a Future that completes when the operations have finished. No useful contents.
    */
  def handleRestored(rec:S3EventNotification.S3EventNotificationRecord,path: String) = {
    val jobModelDAO = getJobModelDAO
    val docId = makeDocId(rec.getS3.getBucket.getName, path)

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

        Future.sequence(updateFutures)
          .map(_=>{
            logger.info(s"Updated jobs for source ID $docId")
            ()
          }).recover({
          case err:Throwable=>
            logger.error(s"Could not update jobs for source ID $docId:${err.getMessage}", err)
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

  protected def getPathCacheIndexName = sys.env.get("PATH_CACHE_INDEX") match {
    case Some(name)=>name
    case None=>
      Option(System.getProperty("PATH_CACHE_INDEX")) match {
        case Some(name)=>name
        case None=>"pathcache"
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

  /**
    * handle an "object restore started" message.
    * This is as simple as updating the database to tell the UI that the restore is running.
    * @param rec S3EventNotification.S3EventNotificationRecord instance
    * @return a Future that completes when the operations have finished. No useful contents.
    */
  def handleStarted(rec:S3EventNotification.S3EventNotificationRecord,path: String) = {
    val jobModelDAO = getJobModelDAO
    val docId = makeDocId(rec.getS3.getBucket.getName, path)

    jobModelDAO.jobsForSource(docId).flatMap(resultList=>{
      val failures = resultList.collect({case Left(err)=>err})
      if(failures.nonEmpty){
        logger.error(s"Could not look up jobs for source ID $docId: ")
        failures.foreach(err=>logger.error(err))
        Future.failed(new RuntimeException(s"Could not look up jobs for source ID $docId"))
      } else {
        val success = resultList.collect({case Right(result)=>result})
        val restoreJobs = success
          .filter(_.jobType=="RESTORE").filter(_.completedAt.isEmpty)
        logger.info(s"Found restore jobs: $restoreJobs")

        val updateFutures = restoreJobs.map(job=>{
          val updatedJob = job.copy(jobStatus = JobStatus.ST_RUNNING)
          jobModelDAO.putJob(updatedJob)
        })

        Future.sequence(updateFutures)
          .map(_=>{
            logger.info(s"Updated jobs for source ID $docId")
            ()
          }).recoverWith({
          case err:Throwable=>
            logger.error(s"Could not update jobs for source ID $docId:${err.getMessage}", err)
            Future.failed(err)
        })
      }
    })
  }

  /**
    * handle an "object restore expired" message.
    * This is as simple as updating the database to tell the UI that the restore has expired.
    * @param rec S3EventNotification.S3EventNotificationRecord instance
    * @return a Future.
    */
  def handleExpired(rec:S3EventNotification.S3EventNotificationRecord,path: String) = {
    val lightboxEntryDAO = getLightboxEntryDAO
    val docId = makeDocId(rec.getS3.getBucket.getName, path)

    lightboxEntryDAO.getFilesForId(docId).flatMap(resultList=>{
      val failures = resultList.collect({case Left(err)=>err})
      if(failures.nonEmpty){
        logger.error(s"Could not look up lightbox entries for $docId: ")
        failures.foreach(err=>logger.error(err))
        Future.failed(new RuntimeException(s"Could not look up lightbox entries for $docId"))
      } else {
        val success = resultList.collect({case Right(result)=>result})
        val boxEntries = success
        logger.info(s"Found light box entries: $boxEntries")

        Future.sequence(
          boxEntries.map(boxEntry=>{
            val updatedEntry = boxEntry.copy(restoreStatus = RestoreStatus.RS_EXPIRED)
            lightboxEntryDAO.put(updatedEntry)
          })
        )
      }
    })
  }

  override def handleRequest(event:S3Event, context:Context): Unit = {
    val indexName = getIndexName

    val clusterEndpoint = getClusterEndpoint

    implicit val s3Client:S3Client = getS3Client
    implicit val elasticClient:ElasticClient = getElasticClient(clusterEndpoint)
    implicit val i:Indexer = getIndexer(indexName)
    implicit val pc:PathCacheIndexer = getPathCacheIndexer(getPathCacheIndexName, elasticClient)

    println(s"Lambda was triggered with: \n${dumpEventData(event, Some("\t"))}")
    val resultList = event.getRecords.asScala.map(rec=>{

      val path = URLDecoder.decode(rec.getS3.getObject.getKey,"UTF-8")

      println(s"Source object is s3://${rec.getS3.getBucket.getName}/$path version ${rec.getS3.getObject.getVersionId} in ${rec.getAwsRegion}")
      println(s"Event was sent by ${rec.getUserIdentity.getPrincipalId}")

      rec.getEventName match {
        /*
        All of these events indicate new content
         */
        case "ObjectCreated:Put"=>
          handleCreated(rec, path)
        case "ObjectCreated:Post"=>
          handleCreated(rec, path)
        case "ObjectCreated:CompleteMultipartUpload"=>
          handleCreated(rec, path)
        case "ObjectCreated:Copy"=>
          handleCreated(rec, path)

        /*
        All of these events indicate something was deleted, or an deletion attempt
         */
        case "ObjectRemoved:Delete"=> //object was _actually_ deleted
          handleRemoved(rec, path)
        case "ReducedRedundancyLostObject"=>
          handleRemoved(rec, path)
        case "LifecycleExpiration:Delete"=>
          handleRemoved(rec, path)
        case "ObjectRemoved:DeleteMarkerCreated"=> //object was _apparently_ deleted, leaving versions behind
          println("ERROR ObjectRemoved:DeleteMarkerCreated not implemented")
          throw new RuntimeException("event was not implemented")
        case "LifecycleExpiration:DeleteMarkerCreated"=>  //lifecycle rule 'deleted' the object, leaving versions behind
          println("ERROR LifecycleExpiration:DeleteMarkerCreated not implemented")
          throw new RuntimeException("event was not implemented")

        /*
        All of these events relate to Glacier restores
         */
        case "ObjectRestore:Post"=> //restore has been initiated
          handleStarted(rec, path)
        case "ObjectRestore:Completed"=>  //restore has been completed
          handleRestored(rec, path)
        case "ObjectRestore:Delete"=>     //restore has expired and been dropped back
          handleExpired(rec, path)

        /*
        This relates to Standard -> IA -> Glacier -> Glacier Deep transitions
         */
        case "LifecycleTransition"=>      //s3 changed the storage tier
          println("ERROR LifecycleTransition not implemented")
          throw new RuntimeException("event was not implemented")

        /*
        Default catch-all that shows an error
         */
        case other:String=>
          println(s"ERROR: received unknown event $other")
          throw new RuntimeException(s"unknown event $other received")
      }
    })

    //need to block here, AWS terminates the lambda as soon as the function returns.
    //as a bonus, if any of the operations fail this raises an exception and therefore is reported as a run failure
    Await.result(Future.sequence(resultList), 45.seconds)
  }
}
