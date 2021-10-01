package helpers

import com.theguardian.multimedia.archivehunter.common.Indexer
import play.api.Configuration

import javax.inject.{Inject, Singleton}

/**
  * this seems ugly, but until I have time to properly go through and refactor how indexer works and is supplied to
  * its users it will have to do
  * @param config
  */
@Singleton
class IndexerFactory @Inject() (config:Configuration){
  def get() = new Indexer(config.get[String]("externalData.indexName"))
}
