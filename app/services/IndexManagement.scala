package services


import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import com.sksamuel.elastic4s.http.ElasticDsl
import com.sksamuel.elastic4s.http.index.mappings.IndexMappings
import com.sksamuel.elastic4s.indexes.AnalysisDefinition
import com.sksamuel.elastic4s.mappings.{BasicField, MappingDefinition, NestedField}

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class IndexManagement @Inject() (config:Configuration, esClientMgr:ESClientManager) extends ElasticDsl {

  import com.sksamuel.elastic4s.mappings.FieldType._
  import com.sksamuel.elastic4s.analyzers._

  private val esClient = esClientMgr.getClient()

  private val indexName = config.get[String]("externalData.indexName")

  def doIndexCreate() = {
    esClient.execute {
      createIndex(indexName).mappings(
        MappingDefinition("entry",
          fields=Seq(
            BasicField("id","keyword"),
            BasicField("etag","keyword"),
            BasicField("region","keyword"),
            NestedField("lightboxEntries", fields=Seq(
              BasicField("owner","keyword"),
              BasicField("avatarUrl","keyword"),
              BasicField("addedAt","date")
            )),
            BasicField("path", "text", fields=Seq(
              BasicField("keyword", "keyword"),
              BasicField("tokens", "text").analyzer(StandardAnalyzer)
            )).analyzer("pathAnalyzer")
          )
        )
      ).analysis(CustomAnalyzerDefinition(
        "pathAnalyzer",
        PathHierarchyTokenizer("pathTokenizer",delimiter="/".toCharArray.head)
      )
      )
    }
  }

  /**
    * checks that we have all the required mappings present and correct
    * @param mappings
    * @return
    */
  def verifyMappings(mappings:Map[String, Map[String, Any]]):Boolean = {
    mappings.keys.foreach(key=>{
      println(s"mapping name $key")
      mappings(key).foreach(kv=>println(s"\t${kv._1}: ${kv._2}"))
    })
    true
  }

  def verifyIndexStatus() = {
    esClient.execute {
      getMapping(indexName)
    }.map(response=>{
      if(response.isError) {
        Left(response.error)
      } else {
        Right(response.result.filter(indexMappings=> !verifyMappings(indexMappings.mappings)))
      }
    })
  }
}
