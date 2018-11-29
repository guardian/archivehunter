import com.google.inject.AbstractModule
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ArchiveHunterConfigurationExt}

class Module extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[ArchiveHunterConfiguration]).to(classOf[ArchiveHunterConfigurationExt])
  }
}
