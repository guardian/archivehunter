import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.theguardian.multimedia.archivehunter.common.{Indexer, MimeType}
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.HttpClient

class InputLambdaMain extends RequestHandler[S3Event, Unit]{
  private final val logger = LogManager.getLogger(getClass)

  override def handleRequest(event:S3Event, context:Context): Unit = {
    val indexName = sys.env.get("INDEX_NAME") match {
      case Some(name)=>name
      case None=>throw new RuntimeException("You must specify an INDEX_NAME in the environment")
    }

    val clusterEndpoint = sys.env.get("ELASTICSEARCH") match {
      case Some(name)=>name
      case None=>throw new RuntimeException("You must specify an Elastic Search cluster endpoint in ELASTICSEARCH in the environment")
    }

    val s3Client = AmazonS3ClientBuilder.defaultClient()
    implicit val elasticClient:HttpClient = HttpClient(ElasticsearchClientUri(clusterEndpoint))
    val i = new Indexer(indexName)

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
      i.indexSingleItem(rec.getS3.getBucket.getName,s3ObjectRef.getKey,s3ObjectRef.geteTag(),s3ObjectRef.getSizeAsLong.longValue(),mimeType)

//      logger.info("Looking up mime type...")
//      MimeType.fromS3Object(rec.getS3.getBucket.getName, s3ObjectRef.getKey).map(mimeType=>{
//        logger.info(s"MIME type for s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} is $mimeType, indexing...")
//        i.indexSingleItem(rec.getS3.getBucket.getName,s3ObjectRef.getKey,s3ObjectRef.geteTag(),s3ObjectRef.getSizeAsLong.longValue(),mimeType)
//      })


    })
  }
}
