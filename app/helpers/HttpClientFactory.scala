package helpers

import akka.http.scaladsl.HttpExt

trait HttpClientFactory {
  def build:HttpExt
}
