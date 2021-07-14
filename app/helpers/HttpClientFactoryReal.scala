package helpers

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpExt}

import javax.inject.Inject

class HttpClientFactoryReal @Inject() (implicit actorSystem:ActorSystem) extends HttpClientFactory {
  override def build: HttpExt = Http()
}
