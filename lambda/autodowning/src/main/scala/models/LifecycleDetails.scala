package models
import scala.collection.JavaConverters._

case class LifecycleDetails (LifecycleActionToken: Option[String], AutoScalingGroupName: String,
                             LifecycleHookName: Option[String],
                             EC2InstanceId:Option[String], LifecycleTransition:Option[String],
                             NotificationMetadata:Option[String], Cause:Option[String])

object LifecycleDetails extends ((Option[String],String,Option[String],Option[String],Option[String],Option[String],Option[String])=>LifecycleDetails)
                        with HashmapExtractors {
  def fromLinkedHashMap(input:java.util.LinkedHashMap[String,Object]) = {
    val converted = input.asScala

    new LifecycleDetails(
      converted.get("LifecycleActionToken").flatMap(x=>getOptionalString(x)),
      getStringValue(converted("AutoScalingGroupName")),
      converted.get("LifecycleHookName").flatMap(x=>getOptionalString(x)),
      converted.get("EC2InstanceId").flatMap(x=>getOptionalString(x)),
      converted.get("LifecycleTransition").flatMap(x=>getOptionalString(x)),
      converted.get("NotificationMetadata").flatMap(x=>getOptionalString(x)),
      converted.get("Cause").flatMap(x=>getOptionalString(x))
    )
  }
}