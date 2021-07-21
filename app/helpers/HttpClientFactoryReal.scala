package helpers

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpExt}

import javax.inject.Inject

/**
  * this simple factory object provides the "real" implementation i.e. returns a genuine HTTP object.
  * In unit tests for methods that use it, this factory is swapped out for a mock one defined in-test via Guice.
  */
class HttpClientFactoryReal @Inject() (implicit actorSystem:ActorSystem) extends HttpClientFactory {
  override def build: HttpExt = Http()
}
