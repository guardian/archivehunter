import clientManagers.{ESClientManager, ESClientManagerImpl}
import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport
import services.{AppStartup, BucketScanner, LegacyProxiesScanner}

class Module extends AbstractModule with AkkaGuiceSupport {
  override def configure() = {
    bind(classOf[ESClientManager]).to(classOf[ESClientManagerImpl])

    bindActor[BucketScanner]("bucketScannerActor")
    bindActor[LegacyProxiesScanner]("legacyProxiesScannerActor")
    bind(classOf[AppStartup]).asEagerSingleton() //do app startup

  }
}
