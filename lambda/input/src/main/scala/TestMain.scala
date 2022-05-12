import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, NoSuchKeyException}

import java.time.{LocalDateTime, ZonedDateTime}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import akka.actor.ActorSystem
import akka.stream.Materializer

object TestMain {
  private val logger = LoggerFactory.getLogger(getClass)

  def getObjectInformation(bucket:String, key:String)(implicit client:S3Client) = {
    Try { client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()) } match {
      case Success(meta)=>
        logger.info(s"Found s3://$bucket/$key with content length ${meta.contentLength()}, etag ${meta.eTag()} and version ${meta.versionId()}")

        Some(new S3EventNotification.S3ObjectEntity(key, meta.contentLength(), meta.eTag(), meta.versionId()))
      case Failure(_:NoSuchKeyException)=>
        logger.error(s"Could not find s3://$bucket/$key")
        None
      case Failure(err)=>
        logger.error(s"Could not find object s3://$bucket/$key: ${err.getMessage}",err)
        System.exit(1)
        None
    }

  }

  def main(args:Array[String]) = {
    implicit val system:ActorSystem = ActorSystem("root")
    implicit val mat:Materializer = Materializer.matFromSystem
    val m = new InputLambdaMain

    if(args.length<2) {
      println("Test program for input lambda. You must specify a bucket name for the first parameter and an object key for the second")
      System.exit(2)
    }
    val bucketName = args.head
    val objectKey = args(1)

    if(bucketName=="" || objectKey=="") {
      println("Test program for input lambda. You must specify a bucket name for the first parameter and an object key for the second")
      System.exit(2)
    }
    implicit val client = S3Client.create()

    getObjectInformation(bucketName, objectKey) match {
      case Some(record) =>
        val fakeEvents = Seq(
          new S3EventNotificationRecord("eu-west-1", "ObjectCreated:Put", "Test", LocalDateTime.now().toString, "1.0",
            null,
            null,
            new S3EventNotification.S3Entity(
              "fake-configuration",
              new S3EventNotification.S3BucketEntity(bucketName, new S3EventNotification.UserIdentityEntity("test"), ""),
              record,
              "unknown"
            ),
            new S3EventNotification.UserIdentityEntity("test")
          )
        )

        val evt = new S3Event(fakeEvents.asJava)
        m.handleRequest(evt, null)
        System.exit(0)
      case None=>
        println("Nothing was found")
        System.exit(1)
    }
  }
}
