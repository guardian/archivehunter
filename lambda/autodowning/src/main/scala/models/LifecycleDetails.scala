package models

/*
  "detail": {
    "LifecycleActionToken":"87654321-4321-4321-4321-210987654321",
    "AutoScalingGroupName":"my-asg",
    "LifecycleHookName":"my-lifecycle-hook",
    "EC2InstanceId":"i-1234567890abcdef0",
    "LifecycleTransition":"autoscaling:EC2_INSTANCE_TERMINATING",
    "NotificationMetadata":"additional-info"
  }
 */

case class LifecycleDetails (LifecycleActionToken: String, AutoScalingGroupName: String, LifecycleHookName: String,
                             EC2InstanceId:Option[String], LifecycleTransition:String, NotificationMetadata:Option[String])

