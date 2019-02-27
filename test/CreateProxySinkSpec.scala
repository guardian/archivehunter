import java.time.ZonedDateTime

import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Keep, Source}
import com.theguardian.multimedia.archivehunter.common._
import com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework.ProxyGenerators
import helpers.{CreateProxySink, EOSDetect}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Success
import scala.concurrent.ExecutionContext.Implicits.global

class CreateProxySinkSpec extends Specification with Mockito {
  val mockedProxyGenerators = mock[ProxyGenerators]
  val mockedProxyLocationDAO = mock[ProxyLocationDAO]

  //test permutations of dot-files without all that akka streams stuff getting in the way
  "CreateProxySink.isDotFile" should {
    "return true if given a path to a dotfile" in {
      val cpSink = new CreateProxySink(mockedProxyGenerators)(mockedProxyLocationDAO)

      cpSink.isDotFile("/path/to/some/.dotfile") must beTrue
    }

    "return true if given a path to a dotfile with extension" in {
      val cpSink = new CreateProxySink(mockedProxyGenerators)(mockedProxyLocationDAO)

      cpSink.isDotFile("/path/to/some/.dotfile.ext") must beTrue
    }

    "return false if given a path to a normal file with extension" in {
      val cpSink = new CreateProxySink(mockedProxyGenerators)(mockedProxyLocationDAO)

      cpSink.isDotFile("/path/to/some/normalfile.ext") must beFalse
    }

    "return true if given a path to a dotfile in the root" in {
      val cpSink = new CreateProxySink(mockedProxyGenerators)(mockedProxyLocationDAO)

      cpSink.isDotFile(".normalfile.ext") must beTrue
    }

    "return false if given a path to a normal file in the root" in {
      val cpSink = new CreateProxySink(mockedProxyGenerators)(mockedProxyLocationDAO)

      cpSink.isDotFile("normalfile.ext") must beFalse
    }
  }

  //integration test to ensure that isDotFile gets called correctly
  "CreateProxySink" should {
    "only request proxies for ArchiveEntries that are not for dot-files" in new AkkaTestkitSpecs2Support {
      val stubProxyGenerators = mock[ProxyGenerators]
      stubProxyGenerators.defaultProxyType(any) returns Some(ProxyType.VIDEO)
      stubProxyGenerators.requestProxyJob(any, any[ArchiveEntry], any) returns Future(Success("something"))
      implicit val stubProxyLocationDAO = mock[ProxyLocationDAO]
      implicit val mat:Materializer = ActorMaterializer.create(system)
      val completionPromise = Promise[Unit]()

      val eosDetect = new EOSDetect[Unit,ArchiveEntry](completionPromise, ())

      val cpSink = new CreateProxySink(stubProxyGenerators)
      val src = Source(Seq(
        ArchiveEntry("shouldwork","mybucket","/path/to/actualFile.ext",None,None,1234L,ZonedDateTime.now(),"noEtag",MimeType("video","mp4"),false,StorageClass.STANDARD,Seq(),false,None),
        ArchiveEntry("shouldwork","mybucket","/path/to/.dotfile.ext",None,None,1234L,ZonedDateTime.now(),"noEtag",MimeType("video","mp4"),false,StorageClass.STANDARD,Seq(),false,None),
        ArchiveEntry("shouldwork","mybucket",".dotfile.ext",None,None,1234L,ZonedDateTime.now(),"noEtag",MimeType("video","mp4"),false,StorageClass.STANDARD,Seq(),false,None),
      ).toStream)

      src.via(eosDetect).to(cpSink).run()
      Await.ready(completionPromise.future, 10.seconds)

      //we expect three requests for each entry; thumbnail, media proxy and analyse. if both run then there will be six.
      there were three(stubProxyGenerators).requestProxyJob(any,any[ArchiveEntry],any)
    }
  }
}
