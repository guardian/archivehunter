package helpers

import com.sksamuel.elastic4s.ElasticsearchClientUri
import javax.inject.Inject
import play.api.Configuration
import com.sksamuel.elastic4s.http.{HttpClient, RequestFailure}

trait ESClientManager {
  def getClient():HttpClient
}

class ESClientManagerImpl @Inject()(config:Configuration) extends ESClientManager {
  val esHost:String = config.get[String]("elasticsearch.hostname")
  val esPort:Int = config.get[Int]("elasticsearch.port")
  def getClient() = HttpClient(ElasticsearchClientUri(esHost, esPort))
}