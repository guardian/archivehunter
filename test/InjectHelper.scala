import play.api.inject.guice.GuiceApplicationBuilder
import scala.reflect.ClassTag

//taken from https://stackoverflow.com/questions/34159857/specs2-how-to-test-a-class-with-more-than-one-injected-dependency
trait InjectHelper {
  val builder = new GuiceApplicationBuilder()
  builder.loadConfiguration
  lazy val injector = (new GuiceApplicationBuilder).injector()

  def inject[T : ClassTag]: T = injector.instanceOf[T]
}