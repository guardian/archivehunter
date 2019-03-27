package responses

import com.sksamuel.elastic4s.http.search.Aggregations
import io.circe.Encoder
import models.ChartFacet
import play.api.Logger

import Numeric.Implicits._

case class ChartDataListResponse[T: io.circe.Encoder](status: String, entries: Seq[ChartDataResponse[T]])

//chartJs compatible data model see https://www.chartjs.org/docs/latest/
case class ChartDataResponse[T : io.circe.Encoder] (name:String, labels:List[String], datasets: List[Dataset[T]])

case class Dataset[T](label: String, data:List[T])

object ChartDataResponse {
  val logger = Logger(getClass)

  //generic subtract method that should work for any pair of Numeric type
  def subtract[T: Numeric](x: T, y: T) : T = implicitly[Numeric[T]].minus(x,y)

  def fromAggregates[T : Encoder](name:String, aggs: Aggregations, labels:List[String]) = {
    val datasets = aggs.data.map(info=> {
      val key = info._1
      val data = info._2.asInstanceOf[Map[String, Any]]

      if (data.contains("value")) {
        Some(new Dataset[T](key, List(data("value").asInstanceOf[T])))
      } else if (data.contains("buckets")) {
        Some(new Dataset[T](key, data("buckets").asInstanceOf[List[Map[String, Any]]].map(entry => {
          entry.get("doc_count").map(_.asInstanceOf[T])
        }).collect({ case Some(value) => value })))
      } else {
        logger.warn(s"${aggs.data} did not contain a recognised data key")
        None
      }
    }).collect({case Some(dataset)=>dataset})

    new ChartDataResponse[T](name, labels, datasets.toList)
  }

  def fromAggregatesMap[T: Encoder](aggs:Map[String, Any], name:String, totalForRemainder:Option[T]=None, subKey:Option[String]=None)(implicit num:Numeric[T]):Either[String,ChartDataResponse[T]] = {
    if(aggs.contains("buckets")){
      val data = aggs("buckets").asInstanceOf[List[Map[String,Any]]]
      Right(new ChartDataResponse[T](name,
        data.map(entry=>entry.getOrElse("key_as_string", entry("key")).asInstanceOf[String]),
        List(Dataset(name, data.map(entry=>entry(subKey.getOrElse("doc_count")).asInstanceOf[T])))
      ))
    } else if(aggs.contains("value")){
      Right(new ChartDataResponse[T](name,
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

  /**
    * return a complete list of unique keys for the provided list of ChartFacet objects
    * @param data sequence of ChartFacets
    * @tparam T type of the data for ChartFacet
    * @return a Sequence of strings representing a single one of each data key contained in the facets
    */
  protected def uniqueKeySets[T](facetData:ChartFacet[T]):List[String] =
    facetData.facets.flatMap(_.values.keys).distinct.toList


  def fromIntermediateRepresentation[T:Encoder](data:Seq[ChartFacet[T]]) = {
    data.map(facetData=>{
      new ChartDataResponse[T](facetData.name,
        uniqueKeySets(facetData),
        facetData.facets.map(entry=>Dataset(entry.name, entry.values.values.toList)).toList
      )
    })
  }

}