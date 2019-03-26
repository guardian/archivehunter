package models

import play.api.Logger

case class ChartFacet[T:io.circe.Encoder](name:String, facets:Seq[ChartFacetData[T]]) {
  private val logger = Logger(getClass)

  /**
    * inverts the arrangement of facets, so instead of:
    * ```
    * facet:{name: "a", "values":[{name:"something",value:10},...]}
    * ```
    * we get
    * ```
    * facet:{name:"something", "values":[{name:"a",value:10},...]}
    * ```
    * @return
    */
  def inverted() = {
    val independentKeys = facets.flatMap(_.values.keys).distinct
    logger.debug(s"got independent keys: $independentKeys")

    new ChartFacet[T](name, independentKeys.map(key=>{
      ChartFacetData[T](key,facets.map(fac=>{
        if(fac.values.contains(key)) {
          Some((fac.name, fac.values(key)))
        } else None
      }).collect({case Some(f)=>f}).toMap)
    }))
  }
}

case class ChartFacetData[T : io.circe.Encoder] (name:String, values:Map[String, T])
