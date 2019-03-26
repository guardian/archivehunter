package responses

import com.sksamuel.elastic4s.http.search.Aggregations
import io.circe.Encoder
import play.api.Logger
import Numeric.Implicits._

case class ChartDataListResponse[T: io.circe.Encoder](status: String, entries: Seq[ChartDataResponse[T]])

//chartJs compatible data model see https://www.chartjs.org/docs/latest/
case class ChartDataResponse[T : io.circe.Encoder] (labels:List[String], datasets: List[Dataset[T]])

case class Dataset[T](label: String, data:List[T])

object ChartDataResponse {
  val logger = Logger(getClass)

  //generic subtract method that should work for any pair of Numeric type
  def subtract[T: Numeric](x: T, y: T) : T = implicitly[Numeric[T]].minus(x,y)

  def fromAggregates[T : Encoder](aggs: Aggregations, labels:List[String]) = {
    val datasets = aggs.data.map(info=> {
      val key = info._1
      val data = info._2.asInstanceOf[Map[String, Any]]

      if (data.contains("value")) {
        Some(new Dataset[T](key, List(data.get("value").get.asInstanceOf[T])))
      } else if (data.contains("buckets")) {
        Some(new Dataset[T](key, data.get("buckets").get.asInstanceOf[List[Map[String, Any]]].map(entry => {
          entry.get("doc_count").map(_.asInstanceOf[T])
        }).collect({ case Some(value) => value })))
      } else {
        logger.warn(s"${aggs.data} did not contain a recognised data key")
        None
      }
    }).collect({case Some(dataset)=>dataset})

    new ChartDataResponse[T](labels, datasets.toList)
  }

  def fromAggregatesMap[T: Encoder](aggs:Map[String, Any], name:String, totalForRemainder:Option[T]=None)(implicit num:Numeric[T]):Either[String,ChartDataResponse[T]] = {
    if(aggs.contains("buckets")){
      val data = aggs("buckets").asInstanceOf[List[Map[String,Any]]]
      Right(new ChartDataResponse[T](
        data.map(entry=>entry.getOrElse("key_as_string", entry("key")).asInstanceOf[String]),
        List(Dataset(name, data.map(entry=>entry("doc_count").asInstanceOf[T])))
      ))
    } else if(aggs.contains("value")){
      Right(new ChartDataResponse[T](
        List(
          Some("Count"),
          totalForRemainder.map(total=>"Remainder")
        ).collect({case Some(value)=>value}),
        List(Dataset(name,List(
          Some(aggs("value").asInstanceOf[T]),
          totalForRemainder.map(total=>{
            subtract(total, aggs("value").asInstanceOf[T])
          })
          ).collect({case Some(value)=>value})
        ))
      ))
    } else {
      Left("Aggregate had neither buckets nor value")
    }


  }
}