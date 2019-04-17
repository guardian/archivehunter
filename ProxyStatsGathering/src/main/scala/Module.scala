import akka.actor.ActorSystem
import com.google.inject.AbstractModule
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, ESClientManagerImpl}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ArchiveHunterConfigurationExt}

class Module(system:ActorSystem) extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[ActorSystem]).toInstance(system)
    bind(classOf[ESClientManager]).to(classOf[ESClientManagerImpl])
    bind(classOf[ArchiveHunterConfiguration]).to(classOf[ArchiveHunterConfigurationExt])
  }
}
