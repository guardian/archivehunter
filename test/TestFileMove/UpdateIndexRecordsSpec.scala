package TestFileMove

import java.time.ZonedDateTime

import akka.actor.Props
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.sksamuel.elastic4s.http.HttpClient
import com.theguardian.multimedia.archivehunter.common._
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.FileMove.GenericMoveActor.{FileMoveTransientData, MoveActorMessage, PerformStep, StepSucceeded}
import services.FileMove.UpdateIndexRecords

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Success

class UpdateIndexRecordsSpec extends Specification with Mockito {
  import akka.pattern.ask
  implicit val timeout:akka.util.Timeout = 30 seconds

  "UpdateIndexRecords!PerformStep" should {
    "create a new index record for the copied file and also register copied proxies" in new AkkaTestkitSpecs2Support {
      implicit val esClient = mock[HttpClient]
      implicit val ddbClient = mock[DynamoClient]

      val mockedIndexer = mock[Indexer]

      val testItem = ArchiveEntry("fake-id","sourcebucket","/path/to/file",None,None,1234L,ZonedDateTime.now(),"fake-etag",
        MimeType.fromString("video/quicktime").right.get,true,StorageClass.STANDARD_IA,Seq(), false,None)

      mockedIndexer.getById(any)(any) returns Future(testItem)
      mockedIndexer.indexSingleItem(any,any,any)(any) returns Future(Success("some-id"))

      val mockedProxyLocationDAO = mock[ProxyLocationDAO]

      val sourceProxyList = Seq(
        ProxyLocation("source-file-id","proxyid1",ProxyType.VIDEO,"source-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid2",ProxyType.AUDIO,"source-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("source-file-id","proxyid3",ProxyType.THUMBNAIL,"source-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )

      val destProxyList = Seq(
        ProxyLocation("dest-file-id","proxyid1",ProxyType.VIDEO,"dest-proxy-bucket","path/to/proxy1",None,StorageClass.STANDARD),
        ProxyLocation("dest-file-id","proxyid2",ProxyType.AUDIO,"dest-proxy-bucket","path/to/proxy2",None,StorageClass.STANDARD),
        ProxyLocation("dest-file-id","proxyid3",ProxyType.THUMBNAIL,"dest-proxy-bucket","path/to/proxy3",None,StorageClass.STANDARD),
      )
      val data = FileMoveTransientData("source-file-id",None,Some("dest-file-id"),Some(sourceProxyList),Some(destProxyList),"dest-media-bucket","dest-proxy-bucket")

      val actor = system.actorOf(Props(new UpdateIndexRecords(mockedIndexer,mockedProxyLocationDAO)))
      val result = Await.result(actor ? PerformStep(data), 30 seconds).asInstanceOf[MoveActorMessage]

      result must beAnInstanceOf[StepSucceeded]

      there was one(mockedIndexer).getById("fake-id")
      there was one(mockedIndexer).indexSingleItem(any)
      there was one(mockedIndexer).deleteById("fake-id")
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList.head)
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(1))
      there was one(mockedProxyLocationDAO).saveProxy(destProxyList(2))
    }
  }
}
