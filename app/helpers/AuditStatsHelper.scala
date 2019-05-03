package helpers

/**
  * helper object that abstracts out query definitions to make debugging and testing easier
  */
import java.time.ZonedDateTime

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import com.sksamuel.elastic4s.searches.DateHistogramInterval
import models.AuditEntryClass
import responses.{ChartDataResponse, Dataset}

object AuditStatsHelper {
  import com.sksamuel.elastic4s.http.ElasticDsl._

  /**
    * returns a query for direct inssertion into client.execute() that returns aggregation on date, with sub-aggregation on user and total size below that
    * this comes back as a set of nested Map[String,Any]
    * @param indexName
    * @return
    */
  def aggregateBySizeAndTimeQuery(indexName:String, forClass:AuditEntryClass.Value = AuditEntryClass.Restore, graphSubLevel:String="requestedBy", dateInterval:DateHistogramInterval = DateHistogramInterval.Month) =
    search(indexName) query matchQuery("entryClass.keyword", forClass.toString) aggregations dateHistogramAgg("byDate","createdAt")
      .interval(dateInterval)
      .subAggregations(
        sumAgg("totalSize", "fileSize"),
        termsAgg("byUser", s"$graphSubLevel.keyword").subAggregations(sumAgg("totalSize", "fileSize"))
      )

  /*
  it makes the code much more readable to use these case classes than manually map everything out of the dictionaries
   */
  case class BucketByUser(userName:String, value:Double)
  object BucketByUser extends ((String,Double)=>BucketByUser) {
    def fromAggMap(mapData:Map[String,Any]) = new BucketByUser(mapData("key").asInstanceOf[String],mapData("totalSize").asInstanceOf[Map[String,Double]]("value"))
  }

  case class BucketByDate(dateValue:String, users:List[BucketByUser])
  object BucketByDate extends((String,List[BucketByUser])=>BucketByDate) {
    def fromAggMap(mapData:Map[String,Any], secondLayerName:String) =
      new BucketByDate(mapData("key_as_string").asInstanceOf[String], mapData(secondLayerName).asInstanceOf[Map[String,Any]]("buckets").asInstanceOf[List[Map[String,Any]]].map(userBucket=>BucketByUser.fromAggMap(userBucket)))
  }

  /**
    * converts the data for our 3-layer aggregation from the generic map as returned by elastic4s into a data format suitable for chartjs.
    * along the way it it uses the above case classes to make the data marshalling somewhat simpler to read
    * @param name name parameter that gets returned in the ChartDataResponse field
    * @param aggregateAsMap aggregation data as returned from Elastic4s.  This should be the specific key of the main dictionary, cast to the right fromat
    *                       i.e. `result.result.aggregationsAsMap("byDate").asInstanceOf[Map[String,Any]]`
    * @return an instance of ChartDataResponse[Double] containing the data
    */
  def sizeTimeAggregateToChartData(name:String, aggregateAsMap:Map[String, Any]) = {
    val bucketsList = aggregateAsMap("buckets").asInstanceOf[List[Map[String,Any]]].map(entry=>BucketByDate.fromAggMap(entry, "byUser"))

    val labelsList = bucketsList.map(_.dateValue)

    val knownUsers = bucketsList.flatMap(bucketEntry=>{
      bucketEntry.users.map(_.userName)
    }).distinct

    val datasets = knownUsers.map(userName=>{
      val bucketContentForUser = bucketsList.map(entry=>{
        //should either be one or zero entry per user, let's find it
        entry.users.find(_.userName==userName).map(_.value)
      })
      Dataset(userName, bucketContentForUser.map(_.getOrElse(0.0)))
    })

    ChartDataResponse(name, labelsList, datasets)
  }

  def monthlyTotalsAggregateQuery(indexName:String, dateInterval:DateHistogramInterval = DateHistogramInterval.Month) =
    search(indexName)
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
