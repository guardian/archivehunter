import java.time.Instant
import java.util.Date

import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.event.S3EventNotification
import com.amazonaws.services.s3.event.S3EventNotification._
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common.Indexer
import org.specs2.mock.Mockito
import org.specs2.mutable._
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.s3.model.ObjectMetadata
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Success
import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class InputLambdaMainSpec extends Specification with Mockito {

  "InputLambdaMain" should {
    "call to index an item delivered via an S3Event" in {
      val fakeEvent = new S3Event(Seq(
        new S3EventNotificationRecord("aws-fake-region","ObjectCreated:Put","unit_test",
        "2018-01-01T11:12:13.000Z","1",
          new RequestParametersEntity("localhost"),
          new ResponseElementsEntity("none","fake-req-id"),
          new S3Entity("fake-config-id",
            new S3BucketEntity("my-bucket", new UserIdentityEntity("owner"),"arn"),
            new S3ObjectEntity("path/to/object",1234L,"fakeEtag","v1"),"1"),
          new UserIdentityEntity("no-principal"))
      ).asJava)

      val fakeContext = mock[Context]
      val mockIndexer = mock[Indexer]
      mockIndexer.indexSingleItem(any,any,any)(any).returns(Future(Success("fake-entry-id")))
      val test = new InputLambdaMain {
        override protected def getElasticClient(clusterEndpoint: String): HttpClient = {
          val m = mock[HttpClient]

          m
        }

        override protected def getIndexer(indexName: String): Indexer = mockIndexer

        override protected def getS3Client: AmazonS3 = {
          val m = mock[AmazonS3]
          val fakeMd = new ObjectMetadata()
          fakeMd.setContentType("video/mp4")
          fakeMd.setContentLength(1234L)
          fakeMd.setLastModified(Date.from(Instant.now()))

          m.getObjectMetadata("my-bucket","path/to/object").returns(fakeMd)
          m
        }
      }

      try {
        test.handleRequest(fakeEvent, fakeContext)
      } catch {
        case ex:Throwable=>
          ex.printStackTrace()
          throw ex
      }
      1 mustEqual 1 //the actual test is that handleRequest does not raise an error
    }
  }
}
