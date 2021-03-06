import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, ESClientManagerImpl}
import com.google.inject.AbstractModule
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import helpers.{ArchiveHunterConfigurationPlay, InjectableRefresher, InjectableRefresherImpl}
import play.api.libs.concurrent.AkkaGuiceSupport
import services._

class Module extends AbstractModule with AkkaGuiceSupport {
  override def configure() = {
    bind(classOf[ESClientManager]).to(classOf[ESClientManagerImpl])
    bind(classOf[ArchiveHunterConfiguration]).to(classOf[ArchiveHunterConfigurationPlay])
    bind(classOf[InjectableRefresher]).to(classOf[InjectableRefresherImpl])

    bindActor[BucketScanner]("bucketScannerActor")
    bindActor[LegacyProxiesScanner]("legacyProxiesScannerActor")
    bindActor[BulkThumbnailer]("bulkThumbnailerActor")
    bindActor[DynamoCapacityActor]("dynamoCapacityActor")
    bindActor[ProxiesRelinker]("proxiesRelinker")
    bindActor[GlacierRestoreActor]("glacierRestoreActor")
    bindActor[JobPurgerActor]("jobPurgerActor")
    bindActor[IngestProxyQueue]("ingestProxyQueue")
    bindActor[ProxyFrameworkQueue]("proxyFrameworkQueue")
    bindActor[ProblemItemRetry]("problemItemRetry")
    bindActor[FileMoveActor]("fileMoveActor")
    bind(classOf[AppStartup]).asEagerSingleton() //do app startup
  }
}
