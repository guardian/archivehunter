import com.theguardian.multimedia.archivehunter.common.ProxyType
import helpers.ProxyLocator
import org.specs2.mutable._

class ProxyLocatorSpec extends Specification {
  "ProxyLocator.stripFileEnding" should {
    "return the base part of a filename with an extension" in {
      val result = ProxyLocator.stripFileEnding("/path/to/some/file_with.extension")
      result mustEqual "/path/to/some/file_with"
    }

    "return the whole filename, if there is no extension" in {
      val result = ProxyLocator.stripFileEnding("/path/to/some/file_without_extension")
      result mustEqual "/path/to/some/file_without_extension"
    }
  }

  "ProxyLocator.proxyTypeForExtension" should {
    "return ProxyType.VIDEO for an obvious video extension" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/videoproxy.mp4")
      result must beSome(ProxyType.VIDEO)
    }

    "return ProxyType.AUDIO for an obvious audio extension" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/audioproxy.mp3")
      result must beSome(ProxyType.AUDIO)
    }

    "return ProxyType.IMAGE for an obvious image extension" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/thumbnail.jpg")
      result must beSome(ProxyType.THUMBNAIL)
    }

    "return ProxyType.UNKNOWN for any other type" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/some/thing.dfsfsdf")
      result must beSome(ProxyType.UNKNOWN)
    }

    "return None for a path that does not have a type" in {
      val result = ProxyLocator.proxyTypeForExtension("path/to/something/withnoextension")
      result must beNone
    }
  }
}
