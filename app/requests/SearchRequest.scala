package requests

import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.searches.sort.SortOrder

/**
  * this class represents a json search request document
  * @param q optional query string
  * @param path optional path to search within
  */
case class SearchRequest (q:Option[String],
                          path:Option[String],
                          collection:Option[String],
                          hideDotFiles:Option[Boolean],
                          sortBy:Option[String],
                          sortOrder:Option[String]) {
  /**
    * converts this SearchRequest object to a sequence of QueryDefinition, suitable for passing to Elastic4s
    * @return a Seq[QueryDefinition] representing the search parameters of this request.
    */
  def toSearchParams = {
    Seq(
      path.map(path=>termQuery("path",path)),
      collection.map(bucket=>termQuery("bucket.keyword", bucket)),
      q.map(queryString=>query(queryString)),
      if(hideDotFiles.getOrElse(true)){
        //ES regex expressions are always anchored, so we have to put a matchall on the start.
        Some(not(regexQuery(("path.keyword",".*/+\\.[^\\.]+"))))
      } else None
    ).collect({case Some(param)=>param})
  }

  def toSortParam:String =
    sortBy match {
      case Some("path")=>"path.keyword"
      case Some("last_modified")=>"last_modified"
      case Some("size")=>"size"
      case _=>"path.keyword"
    }


  def toSortOrder:SortOrder =
    sortOrder match {
      case Some("desc")=>SortOrder.Desc
      case Some("Descending")=>SortOrder.Desc
      case _=>SortOrder.Asc
    }

}