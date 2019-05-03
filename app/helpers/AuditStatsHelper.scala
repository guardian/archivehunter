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
  def aggregateBySizeAndTimeQuery(indexName:String, forClass:AuditEntryClass.Value = AuditEntryClass.Restore, dateInterval:DateHistogramInterval = DateHistogramInterval.Month) =
    search(indexName) query matchQuery("entryClass.keyword", forClass.toString) aggregations dateHistogramAgg("byDate","createdAt")
      .interval(dateInterval)
      .subAggregations(
        sumAgg("totalSize", "fileSize"),
        termsAgg("byUser", "requestedBy.keyword").subAggregations(sumAgg("totalSize", "fileSize"))
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

  def sizeTimeAggregateToChartData(name:String, aggregateAsMap:Map[String, Any]) = {
//    val bucketsList = aggregateAsMap("buckets").asInstanceOf[List[Map[String,Any]]]
//    val labelsList = bucketsList.map(_("key_as_string").asInstanceOf[String])

//    val knownUsers = bucketsList.flatMap(entry=>{
//      val bucketsByUser = entry("byUser").asInstanceOf[Map[String,Any]]("buckets").asInstanceOf[List[Map[String,Any]]]
//      bucketsByUser.map(_("key").asInstanceOf[String])
//    }).distinct
//
//    val datasets = knownUsers.flatMap(userName=>{
//      bucketsList.map(entry=> {
//        val bucketsByUser = entry("byUser").asInstanceOf[Map[String, Any]]("buckets").asInstanceOf[List[Map[String, Any]]]
//
//        Dataset(userName, bucketsByUser.filter(_.get("key").contains(userName).headOption.map(bucketByUser=>bucketByUser("totalSize").asInstanceOf[Map[String,Double]]("value")).getOrElse(0.0)))
//      })
//    })

    val bucketsList = aggregateAsMap("buckets").asInstanceOf[List[Map[String,Any]]].map(entry=>BucketByDate.fromAggMap(entry, "byUser"))

    println(s"Bucketslist is $bucketsList")
    val labelsList = bucketsList.map(_.dateValue)
    println(s"labelsList is $labelsList")

    val knownUsers = bucketsList.flatMap(bucketEntry=>{
      bucketEntry.users.map(_.userName)
    }).distinct

    println(s"knownUsers is $knownUsers")

    val datasets = knownUsers.map(userName=>{
      val bucketContentForUser = bucketsList.map(entry=>{
        //should either be one or zero entry per user, let's find it
        entry.users.find(_.userName==userName).map(_.value)
      })
      Dataset(userName, bucketContentForUser.map(_.getOrElse(0.0)))
    })

    println(s"datasets are $datasets")

    ChartDataResponse(name, labelsList, datasets)
  }

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
