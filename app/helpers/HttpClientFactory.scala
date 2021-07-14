package helpers

import akka.http.scaladsl.HttpExt

/**
  * very small trait defining an injectable factory for an Http instance.
  * This is to allow a mock Http instance to be injected for testing, in the Auth controller spec
  */
trait HttpClientFactory {
  def build:HttpExt
}
