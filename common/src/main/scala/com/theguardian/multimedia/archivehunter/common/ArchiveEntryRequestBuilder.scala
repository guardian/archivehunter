package com.theguardian.multimedia.archivehunter.common
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.streams.RequestBuilder

/**
  * mix in this class to be able to use the streaming API in Elastic4s
  */
trait ArchiveEntryRequestBuilder extends ZonedDateTimeEncoder with StorageClassEncoder{
  val indexName:String  //anyone extending this class must set the index name
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._
  import io.circe.generic.auto._

  implicit val indexRequestBuilder = new RequestBuilder[ArchiveEntry] {
    // the request returned doesn't have to be an index - it can be anything supported by the bulk api
    def request(t: ArchiveEntry) = {
      println(s"indexRequestBuilder - $indexName building request from $t")
      update(t.id).in(s"$indexName/entry").docAsUpsert(t)
    }
  }
}
