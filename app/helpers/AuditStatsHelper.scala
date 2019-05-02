package helpers

/**
  * helper object that abstracts out query definitions to make debugging and testing easier
  */
import java.time.ZonedDateTime

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import com.sksamuel.elastic4s.searches.DateHistogramInterval
import models.AuditEntryClass

object AuditStatsHelper {
  import com.sksamuel.elastic4s.http.ElasticDsl._

  /**
    * returns a query for direct inssertion into client.execute() that returns aggregation on date, with sub-aggregation on user and total size below that
    * this comes back as a set of nested Map[String,Any]
    * @param indexName
    * @return
    */
  def aggregateBySizeAndTimeQuery(indexName:String, forClass:AuditEntryClass.Value = AuditEntryClass.Restore, dateInterval:DateHistogramInterval = DateHistogramInterval.Month) =
    search(indexName) query matchQuery("entryClass.keyword", forClass.toString) aggregations dateHistogramAgg("byDate","createdAt")
      .interval(dateInterval)
      .subAggregations(
        sumAgg("totalSize", "fileSize"),
        termsAgg("byUser", "requestedBy.keyword").subAggregations(sumAgg("totalSize", "fileSize"))
      )

  /**
    * returns a query to get the total data for the specified audit entry class for the given calendar month
    * @param indexName
    * @param month
    * @param year
    * @return
    */
  def totalDataForMonth(indexName:String, month:Int, year:Int, forClass:AuditEntryClass.Value = AuditEntryClass.Restore) =
    search(indexName) query boolQuery().withMust(
      matchQuery("entryClass.keyword", forClass.toString),
      rangeQuery("createdAt")
        .gte(f"$year%04d-$month%02d")
        .lt(f"$year%04d-${month+1}%02d")
        .format("yyyy-MM")
    ) aggregations sumAgg("totalSize", "fileSize")
}
