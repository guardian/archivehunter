package models

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._

case class LifecycleMessage (
                            version:Int,
                            id:String,
                            detailType: String,
                            source: String,
                            account: String,
                            time: ZonedDateTime,
                            region: String,
                            resources: Seq[String],
                            detail: Option[LifecycleDetails]
                            )

object LifecycleMessage extends((Int,String,String,String,String,ZonedDateTime,String,Seq[String],Option[LifecycleDetails])=>LifecycleMessage)
                        with HashmapExtractors {

  def fromLinkedHashMap(input:java.util.LinkedHashMap[String,Object]) = {
    val converted = input.asScala

    new LifecycleMessage(
      getIntValue(converted("version")),
      getStringValue(converted("id")),
      getStringValue(converted("detail-type")),
      getStringValue(converted("source")),
      getStringValue(converted("account")),
      ZonedDateTime.parse(getStringValue(converted("time")), DateTimeFormatter.ISO_DATE_TIME),
      getStringValue(converted("region")),
      getValueSeq(converted("resources")),
      converted.get("detail").map(_.asInstanceOf[java.util.LinkedHashMap[String, Object]]).map(LifecycleDetails.fromLinkedHashMap)
    )
  }
}