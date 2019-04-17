package com.theguardian.multimedia.archivehunter.common

import com.sksamuel.elastic4s.streams.RequestBuilder
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ProblemItem, ProxyHealthEncoder}

/**
  * mix in this class to be able to use the streaming API in Elastic4s
  */
trait ProblemItemRequestBuilder extends ProxyTypeEncoder with ProxyHealthEncoder {
  val problemsIndexName:String  //anyone extending this class must set the index name
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.http.ElasticDsl._
  import io.circe.generic.auto._

  implicit val indexRequestBuilder = new RequestBuilder[ProblemItem] {
    // the request returned doesn't have to be an index - it can be anything supported by the bulk api
    def request(t: ProblemItem) =
      update(t.fileId).in(s"$problemsIndexName/problem").docAsUpsert(t)
  }
}
