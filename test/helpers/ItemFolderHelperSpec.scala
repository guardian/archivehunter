package helpers

import TestFileMove.AkkaTestkitSpecs2Support
import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import java.time.ZonedDateTime
import scala.concurrent.Await
import scala.concurrent.duration._

class ItemFolderHelperSpec extends Specification with Mockito {
  def archiveEntryGenerator(idString:String, pathString:String) = ArchiveEntry(
    idString,
    "testbucket",
    pathString,
    None,
    None,
    1234L,
    ZonedDateTime.now(),
    "",
    MimeType("application","octet-stream"),
    false,
    StorageClass.STANDARD,
    Seq(),
    false,
    None
  )

  val fakeAssetList = Seq(
    archiveEntryGenerator("1","path/to/file1"),
    archiveEntryGenerator("2", "path/to/file2"),
    archiveEntryGenerator("2a","path/of/file3"),
    archiveEntryGenerator("3","another/path/to/file1"),
    archiveEntryGenerator("4","this/thing/here"),
    archiveEntryGenerator("4a","another/file.mxf"),
    archiveEntryGenerator("5","fileatroot.mp4")
  )

  "ItemFilterHelper.scanFolders" should {
    "return the root level folder list" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = ActorMaterializer()
      val mockClientMgr = mock[ESClientManager]

      val mockGetIndexSource = mock[(String,String,Option[String])=>Unit]

      val helper = new ItemFolderHelper(mockClientMgr) {
        override protected def getIndexSource(indexName: String, forCollection: String, prefix:Option[String]): Source[ArchiveEntry, NotUsed] = {
          mockGetIndexSource(indexName, forCollection, None)
          Source.fromIterator(()=>fakeAssetList.toIterator)
        }
      }

      val result = Await.result(helper.scanFolders("some-index","collection-name",None), 2.seconds)
      result.headOption must beSome("path/")
      result.length mustEqual 3
      result(1) mustEqual "another/"
      result(2) mustEqual "this/"

      there was one(mockGetIndexSource).apply("some-index","collection-name", None)
    }

    "return paths under a prefix" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = ActorMaterializer()
      val mockClientMgr = mock[ESClientManager]

      val helper = new ItemFolderHelper(mockClientMgr) {
        override protected def getIndexSource(indexName: String, forCollection: String, prefix:Option[String]): Source[ArchiveEntry, NotUsed] = {
          Source.fromIterator(()=>fakeAssetList.toIterator)
        }
      }

      val result = Await.result(helper.scanFolders("some-index","collection-name",Some("path/")), 2.seconds)
      result.headOption must beSome("path/to/")
      result.length mustEqual 2
      result(1) mustEqual "path/of/"

      val anotherResult = Await.result(helper.scanFolders("some-index","collection-name", Some("another/path")), 2.seconds)
      anotherResult.headOption must beSome("another/path/to/")
      anotherResult.length mustEqual 1
    }
  }
}
