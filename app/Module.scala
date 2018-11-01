import com.google.inject.AbstractModule
import helpers.{ESClientManager, ESClientManagerImpl}

class Module extends AbstractModule {
  override def configure() = {
    bind(classOf[ESClientManager]).to(classOf[ESClientManagerImpl])
  }
}
