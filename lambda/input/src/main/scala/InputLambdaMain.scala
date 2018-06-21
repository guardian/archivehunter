import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.theguardian.multimedia.archivehunter.common.Indexer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import com.amazonaws.services.s3.model.S3ObjectSummary

class InputLambdaMain extends RequestHandler[S3Event, Unit]{
  private final val logger = LogManager.getLogger(getClass)

  override def handleRequest(event:S3Event, context:Context): Unit = {
    val indexName = sys.env.get("INDEX_NAME") match {
      case Some(name)=>name
      case None=>throw new RuntimeException("You must specify an INDEX_NAME in the environment")
    }

    val i = new Indexer(indexName)

    println(s"Lambda was triggered with $event")
    event.getRecords.forEach(rec=>{
      val s3ObjectRef = rec.getS3.getObject

      val md = s3Client.getObjectMetadata(rec.getS3.getBucket.getName, rec.getS3.getObject.getKey)

      println(s"Source object is s3://${rec.getS3.getBucket.getName}/${rec.getS3.getObject.getKey} in ${rec.getAwsRegion}")
      i.indexSingleItem(rec.getS3.getBucket.getName,s3ObjectRef.getKey,s3ObjectRef.geteTag(),s3ObjectRef.getSizeAsLong.longValue(),s3ObjectRef.)
      println(s"Event was sent by ${rec.getUserIdentity}")
    })
  }
}
