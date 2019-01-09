package requests

import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.searches.sort.SortOrder

/**
  * this class represents a json search request document
  * @param q optional query string
  * @param path optional path to search within
  */
case class SearchRequest (q:Option[String], path:Option[String], collection:Option[String], sortBy:Option[String], sortOrder:Option[String]) {
  def toSearchParams = {
    Seq(
      path.map(path=>termQuery("path",path)),
      collection.map(bucket=>termQuery("bucket.keyword", bucket)),
      q.map(queryString=>query(queryString))
    ).collect({case Some(param)=>param})
  }

  def toSortParam:String = {
    sortBy.map({
      case "path"=>Some("path.keyword")
      case "lastModified"=>Some("lastModified")
      case "size"=>Some("size")
      case _=>None
    }).getOrElse("path.keyword")
  }

  def toSortOrder:SortOrder = {
    sortOrder match {
      case Some("desc")=>SortOrder.Desc
      case Some("descending")=>SortOrder.Desc
      case _=>SortOrder.Asc
    }
  }
}