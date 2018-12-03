package models

import java.time.ZonedDateTime

import com.amazonaws.regions.Regions


/*
{
  "version": "0",
  "id": "12345678-1234-1234-1234-123456789012",
  "detail-type": "EC2 Instance-terminate Lifecycle Action",
  "source": "aws.autoscaling",
  "account": "123456789012",
  "time": "yyyy-mm-ddThh:mm:ssZ",
  "region": "us-west-2",
  "resources": [
    "auto-scaling-group-arn"
  ],
  "detail": {
    "LifecycleActionToken":"87654321-4321-4321-4321-210987654321",
    "AutoScalingGroupName":"my-asg",
    "LifecycleHookName":"my-lifecycle-hook",
    "EC2InstanceId":"i-1234567890abcdef0",
    "LifecycleTransition":"autoscaling:EC2_INSTANCE_TERMINATING",
    "NotificationMetadata":"additional-info"
  }
}
 */

case class LifecycleMessage (
                            version:Int,
                            id:String,
                            detailType: String,
                            source: String,
                            account: String,
                            time: ZonedDateTime,
                            region: Regions,
                            resources: Seq[String],
                            detail: Option[LifecycleDetails]
                            )
