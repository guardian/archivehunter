package helpers

import TestFileMove.AkkaTestkitSpecs2Support
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, MimeType, StorageClass}
import models.PathCacheEntry
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.duration._
import java.time.ZonedDateTime
import scala.concurrent.Await

class PathCacheExtractorSpec extends Specification with Mockito {
  "PathCacheExtractor" should {
    "yield a PathCacheEntry for every element of the path of the incoming ArchiveEntry" in new AkkaTestkitSpecs2Support {
      implicit val mat:Materializer = ActorMaterializer()

      val resultFut = Source.single(ArchiveEntry("aaa","testbucket","path/to/some/very/long/filename.wav",None, Some(".wav"),
        1234L, ZonedDateTime.now(),"",MimeType("application","octet-stream"), false, StorageClass.STANDARD,Seq(),
        false, None))
        .via(PathCacheExtractor())
        .toMat(Sink.seq)(Keep.right)
        .run()

      val result = Await.result(resultFut, 2.seconds)

      result.head mustEqual PathCacheEntry(5,"path/to/some/very/long/",Some("path/to/some/very"),"testbucket")
      result(1) mustEqual PathCacheEntry(4,"path/to/some/very/",Some("path/to/some"),"testbucket")
      result(2) mustEqual PathCacheEntry(3,"path/to/some/",Some("path/to"),"testbucket")
      result(3) mustEqual PathCacheEntry(2,"path/to/",Some("path"),"testbucket")
      result(4) mustEqual PathCacheEntry(1,"path/", None, "testbucket")

      result.length mustEqual 5
    }
  }
}
