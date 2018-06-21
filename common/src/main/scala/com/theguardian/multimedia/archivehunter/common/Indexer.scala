package com.theguardian.multimedia.archivehunter.common

import com.sksamuel.elastic4s.RefreshPolicy

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import com.sksamuel.elastic4s.http.{HttpClient, RequestFailure}
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class Indexer(indexName:String) {
  import com.sksamuel.elastic4s.http.ElasticDsl._

  /**
    * Requests that a single item be added to the index
    * @param s3Bucket
    * @param s3Path
    * @param etagString
    * @param size
    * @param contentType
    * @param client
    * @return a Future containing a Try with either the ID of the new item or a RuntimeException containing the failure
    */
  def indexSingleItem(s3Bucket: String, s3Path: String, etagString: String, size:Long, contentType: MimeType, refreshPolicy: RefreshPolicy=RefreshPolicy.WAIT_UNTIL)(implicit client:HttpClient):Future[Try[String]] =
    client.execute {
      indexInto(indexName / "entry").fields("bucket"->s3Bucket,"path"->s3Path, "eTag"->etagString).refresh(refreshPolicy)
    }.map({
      case Left(failure)=>Failure(new RuntimeException(failure.error.toString))
      case Right(success)=>Success(success.result.id)
    })

  /**
    * Creates a new index, based on the name that has been provided
    * @param client
    * @return
    */
//  def createIndex(implicit client:HttpClient):Future[Try[String]] = client.execute {
//
//  }.map({
//    case Left(failure)=>Failure(new RuntimeException(failure.error.toString))
//    case Right(success)=>Success(success.toString)
//  })
}