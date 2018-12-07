package requests

import com.sksamuel.elastic4s.http.ElasticDsl._

/**
  * this class represents a json search request document
  * @param q optional query string
  * @param path optional path to search within
  */
case class SearchRequest (q:Option[String], path:Option[String], collection:Option[String]) {
  def toSearchParams = {
    Seq(
      path.map(path=>termQuery("path",path)),
      collection.map(bucket=>termQuery("bucket.keyword", bucket)),
      q.map(queryString=>query(queryString))
    ).collect({case Some(param)=>param})
  }
}