package com.theguardian.multimedia.archivehunter

import java.time.ZonedDateTime
import com.sksamuel.elastic4s.embedded.LocalNode
import com.sksamuel.elastic4s.http.{ElasticClient, HttpClient}
import com.sksamuel.elastic4s.http.index.CreateIndexResponse
import com.theguardian.multimedia.archivehunter.common.{ArchiveEntry, Indexer, MimeType, StorageClass}
import org.elasticsearch.client.ElasticsearchClient
import org.specs2.mutable._
import org.specs2.specification.AfterAll

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import java.io._

class IndexerSpec extends Specification with AfterAll {
  val localNode = LocalNode("testcluster","/tmp/ah-test-data")
  def recursive_delete(file: File) {
    if (file.isDirectory)
      Option(file.listFiles).map(_.toList).getOrElse(Nil).foreach(recursive_delete(_))
    file.delete
  }

  def afterAll():Unit = {
    localNode.close()
    recursive_delete(new File("/tmp/ah-test-data"))
  }

  "Indexer.indexSingleItem" should {
    "add a single item to the index" in {
      implicit val client:ElasticClient = localNode.client(shutdownNodeOnClose = false)

      val entry = ArchiveEntry(
        "sfdfsdjfsdhjfsd",
        "mybucket",
        "path/to/my/file",
        Some("region"),
        Some("ext"),
        12345L,
        ZonedDateTime.now(),
        "etag_here",
        MimeType("application","octet-stream"),
        proxied=false,
        storageClass = StorageClass.STANDARD,
        Seq(),
        false,
        None
      )

      val i = new Indexer("testindex")
      val result = Await.result(i.indexSingleItem(entry), 5 seconds)
      result must beRight("sfdfsdjfsdhjfsd")

    }
  }

  "Indexer.newIndex" should {
    "error if the index already exists" in {
      implicit val client:ElasticClient = localNode.client(shutdownNodeOnClose = false)

      val i = new Indexer("testindexexisting")
      Await.result(i.newIndex(2,3), 3 seconds)
      val result = Await.result(i.newIndex(2,3), 3 seconds)
      result must beFailedTry
    }

    "create a new index" in {
      implicit val client:ElasticClient = localNode.client(shutdownNodeOnClose = false)

      val i = new Indexer("testindexnewname")
      val result = Await.result(i.newIndex(2,3), 3 seconds)
      result must beSuccessfulTry(CreateIndexResponse(true,true))
    }
  }
}
