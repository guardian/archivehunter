package helpers

import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ExtValueConverters}
import com.typesafe.config.Config
import javax.inject.Inject
import play.api.{ConfigLoader, Configuration}

/**
  * simple implementation of ArchiveHunterConfiguration that delegates to Play config.
  * This is so that we can re-bind the injectable component to run without Play in the lambda environment
  * @param playConfig injected instance of play.api.Configuration
  */
class ArchiveHunterConfigurationPlay @Inject() (playConfig: Configuration) extends ArchiveHunterConfiguration with ExtValueConverters {

  override def getOptional[T](key:String)(implicit converter: String=>T): Option[T] = {
    implicit val configLoader:ConfigLoader[T] = new ConfigLoader[T] {
      override def load(config: Config, path: String): T = {
        converter(config.getString(path))
      }
    }
      playConfig.getOptional[T](key)
  }

  override def get[T](key:String)(implicit converter: String=>T):T = {
    implicit val configLoader:ConfigLoader[T] = new ConfigLoader[T] {
      override def load(config: Config, path: String): T = {
        converter(config.getString(path))
      }
    }

    playConfig.get[T](key)
  }
}
