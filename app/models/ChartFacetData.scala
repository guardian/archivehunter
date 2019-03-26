package models

case class ChartFacet[T](name:String, facets:Seq[ChartFacetData[T]])

case class ChartFacetData[T : io.circe.Encoder] (name:String, values:Map[String, T])
