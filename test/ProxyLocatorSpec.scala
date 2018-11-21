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
}
