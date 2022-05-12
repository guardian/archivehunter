import akka.actor.ActorSystem
import com.google.inject.AbstractModule
import com.theguardian.multimedia.archivehunter.common.clientManagers.{ESClientManager, ESClientManagerImpl}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ArchiveHunterConfigurationExt}
import akka.stream.Materializer

class Module(system:ActorSystem, mat:Materializer) extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[ActorSystem]).toInstance(system)
    bind(classOf[ESClientManager]).to(classOf[ESClientManagerImpl])
    bind(classOf[ArchiveHunterConfiguration]).to(classOf[ArchiveHunterConfigurationExt])
    bind(classOf[Materializer]).toInstance(mat)
  }
}
