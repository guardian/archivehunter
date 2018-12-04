package models
import scala.collection.JavaConverters._

case class LifecycleDetails (LifecycleActionToken: Option[String], AutoScalingGroupName: Option[String],
                             LifecycleHookName: Option[String],
                             EC2InstanceId:Option[String], LifecycleTransition:Option[String],
                             NotificationMetadata:Option[String], Cause:Option[String], state:Option[String])

object LifecycleDetails extends ((Option[String],Option[String],Option[String],Option[String],Option[String],Option[String],Option[String],Option[String])=>LifecycleDetails)
                        with HashmapExtractors {
  def anyOf(inputs:Object*)=
    inputs.collectFirst({case Some(obj)=>obj})

  def fromLinkedHashMap(input:java.util.LinkedHashMap[String,Object]) = {
    val converted = input.asScala

    new LifecycleDetails(
      converted.get("LifecycleActionToken").flatMap(x=>getOptionalString(x)),
      converted.get("AutoScalingGroupName").flatMap(x=>getOptionalString(x)),
      converted.get("LifecycleHookName").flatMap(x=>getOptionalString(x)),
      anyOf(converted.get("EC2InstanceId"),converted.get("instance-id")).flatMap(x=>getOptionalString(x.asInstanceOf[Object])),
      converted.get("LifecycleTransition").flatMap(x=>getOptionalString(x)),
      converted.get("NotificationMetadata").flatMap(x=>getOptionalString(x)),
      converted.get("Cause").flatMap(x=>getOptionalString(x)),
      converted.get("state").flatMap(x=>getOptionalString(x)),
    )
  }
}