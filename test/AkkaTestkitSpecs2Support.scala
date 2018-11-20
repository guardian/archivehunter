import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.specs2.mutable.After

import scala.concurrent.Await
import scala.concurrent.duration._

/* A tiny class that can be used as a Specs2 ‘context’. */
abstract class AkkaTestkitSpecs2Support extends TestKit(ActorSystem())
  with After
  with ImplicitSender {
  // make sure we shut down the actor system after all tests have run
  def after = Await.result(system.terminate(), 30 seconds)
}