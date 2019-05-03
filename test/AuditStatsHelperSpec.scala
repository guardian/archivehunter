import java.time.ZonedDateTime

import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.embedded.LocalNode
import com.sksamuel.elastic4s.http.ElasticDsl.{createIndex, deleteIndex, index, indexExists, matchAllQuery, search}
import com.sksamuel.elastic4s.mappings.{BasicFieldDefinition, MappingDefinition}
import com.theguardian.multimedia.archivehunter.common.clientManagers.ESClientManager
import helpers.{AuditEntryRequestBuilder, AuditStatsHelper}
import models.{AuditEntry, AuditEntryClass}
import org.specs2.mutable._
import org.specs2.specification.BeforeAfterAll
import io.circe.generic.auto._
import responses.{ChartDataResponse, Dataset}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class AuditStatsHelperSpec extends Specification with InjectHelper with BeforeAfterAll with AuditEntryRequestBuilder {
  sequential

  import com.sksamuel.elastic4s.http.ElasticDsl._
  import com.sksamuel.elastic4s.circe._

  val localNode = LocalNode("testcluster","/tmp/auditstats-helper-testdata")
  private val esClientMgr = inject[ESClientManager]
  private implicit val esClient = esClientMgr.getClient()
  val indexName = "audit-entry"

  def beforeAll = {
    val existsResult = Await.result(esClient.execute {indexExists(indexName)}, 10 seconds)
    if(existsResult.isLeft){
      println(s"Could not check for test index existence: ${existsResult.left.get}")
    } else {
      if(existsResult.right.get.result.exists) Await.ready(esClient.execute {deleteIndex(indexName)}, 10 seconds)
    }

    val createResult = Await.result(esClient.execute {createIndex(indexName) mappings
      MappingDefinition("auditentry", fields=Seq(
        BasicFieldDefinition("fileSize", "long")
      ))
    }, 10 seconds)
    if(createResult.isLeft){
      println(s"Could not create test index: ${createResult.left.get}")
    }

    val testDataSeq = Seq(
      AuditEntry("fileid-1",2,"here","test1",ZonedDateTime.parse("2019-01-01T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2,"here","test1",ZonedDateTime.parse("2019-02-02T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2,"here","test2",ZonedDateTime.parse("2019-02-04T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2,"here","test1",ZonedDateTime.parse("2019-03-03T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2,"here","test3",ZonedDateTime.parse("2019-03-03T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
      AuditEntry("fileid-1",2,"here","test3",ZonedDateTime.parse("2019-03-05T00:00:00Z"),"some-collection",AuditEntryClass.Restore,Some("5f4d9eb3-7605-4e90-9b81-598641b0f0cb")),
    )

    try {
      val futureList = testDataSeq.map(entry => esClient.execute {
        index(indexName,"auditentry") doc entry refresh(RefreshPolicy.Immediate)
      })
      val result = Await.result(Future.sequence(futureList), 10 seconds)
      val failures = result.collect({ case Left(err) => err })
      if (failures.nonEmpty) failures.foreach(err => println(s"Could not set up data: $err"))

    } catch {
      case err:Throwable=>
        println(s"Could not set up data:", err)
    }
  }

  def afterAll = {
    localNode.close()
  }

  "aggregateBySizeAndTimeQuery" should {
    "return a query that aggregates total size by time" in {
      val resultFuture = esClient.execute {
        AuditStatsHelper.aggregateBySizeAndTimeQuery(indexName)
      }
      val result = Await.result(resultFuture, 10 seconds)
      println(s"Got $result")
      val aggregations = result.right.get.result.aggregationsAsMap
      println(s"got $aggregations")
      result must beRight

      val bucketsList = aggregations("byDate").asInstanceOf[Map[String,Any]]("buckets").asInstanceOf[List[Map[String,Any]]]

      //TODO: once we have sorted out data formatting for graphing then improve assertions here
      bucketsList.head("key_as_string") mustEqual "2019-01-01T00:00:00.000Z"
      bucketsList.head.get("byUser") must not beNone
      val firstMonthUserBreakdown = bucketsList.head("byUser").asInstanceOf[Map[String,Any]]("buckets").asInstanceOf[List[Map[String,Any]]]
      firstMonthUserBreakdown.head("key") mustEqual "test1"
      firstMonthUserBreakdown.head("totalSize") mustEqual Map("value"->2.0)
    }
  }

  "totalDataForMonth" should {
    "return a query that sums the total size for the given month/year" in {
      val resultFuture = esClient.execute {
        AuditStatsHelper.totalDataForMonth(indexName,3,2019)
      }
      val result = Await.result(resultFuture, 10 seconds)
      println(s"Got $result")
      val aggregations = result.right.get.result.aggregationsAsMap
      println(s"got $aggregations")
      result must beRight
      val finalResult = aggregations("totalSize").asInstanceOf[Map[String,Double]]("value")
      finalResult mustEqual 6
    }
  }

  "sizeTimeAggregateToChartData" should {
    "take raw aggregations data and convert it into a ChartDataResponse" in {
      val data:Map[String,Any] = Map("byDate" ->
        Map("buckets" -> List(
          Map("key_as_string" -> "2019-01-01T00:00:00.000Z", "byUser" ->
            Map("doc_count_error_upper_bound" -> 0, "sum_other_doc_count" -> 0, "buckets" ->
              List(
                Map("key"-> "test1", "doc_count" -> 1, "totalSize" -> Map("value" -> 2.0))
              )
            ), "totalSize" -> Map("value" -> 2.0), "key" -> 1546300800000L, "doc_count" -> 1
          ),
          Map("key_as_string" ->"2019-02-01T00:00:00.000Z", "byUser" ->
            Map("doc_count_error_upper_bound" -> 0, "sum_other_doc_count" -> 0, "buckets" ->
              List(
                Map("key" -> "test1", "doc_count" -> 1, "totalSize" -> Map("value" -> 2.0)),
                Map("key" -> "test2", "doc_count" -> 1, "totalSize" -> Map("value" -> 2.0))
              )
            ), "totalSize" -> Map("value" -> 4.0), "key" -> 1548979200000L, "doc_count" -> 2
          ),
          Map("key_as_string" -> "2019-03-01T00:00:00.000Z", "byUser" ->
            Map("doc_count_error_upper_bound" -> 0, "sum_other_doc_count" -> 0, "buckets" ->
              List(
                Map("key" -> "test3", "doc_count" -> 2, "totalSize" -> Map("value" -> 4.0)),
                Map("key" -> "test1", "doc_count" -> 1, "totalSize" -> Map("value" -> 2.0))
              )
            ), "totalSize" -> Map("value" -> 6.0), "key" -> 1551398400000L, "doc_count" -> 3
          )
        ))
      )

      val result = AuditStatsHelper.sizeTimeAggregateToChartData("test chart",data("byDate").asInstanceOf[Map[String,Any]])
      result mustEqual ChartDataResponse("test chart",
        List("2019-01-01T00:00:00.000Z","2019-02-01T00:00:00.000Z","2019-03-01T00:00:00.000Z"),
        List(Dataset("test1",List(2.0,2.0,2.0)), Dataset("test2",List(0.0,2.0,0.0)), Dataset("test3",List(0.0,0.0,4.0)))
      )
    }
  }
}
