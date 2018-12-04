import java.time.{ZoneId, ZonedDateTime}

import com.amazonaws.regions.Regions
import models.{LifecycleDetails, LifecycleMessage, LifecycleMessageDecoder}
import org.specs2.mutable._
import io.circe.generic.auto._
import io.circe.syntax._

class LifecycleMessageSpec extends Specification with LifecycleMessageDecoder {
//  "LifecycleMessageDecoder.decodeLifecycleMessage" should {
//    "decode example json" in {
//      val exampleJson = """{
//                          |  "version": "0",
//                          |  "id": "12345678-1234-1234-1234-123456789012",
//                          |  "detail-type": "EC2 Instance-terminate Lifecycle Action",
//                          |  "source": "aws.autoscaling",
//                          |  "account": "123456789012",
//                          |  "time": "2017-01-02T13:14:15Z",
//                          |  "region": "us-west-2",
//                          |  "resources": [
//                          |    "auto-scaling-group-arn"
//                          |  ],
//                          |  "detail": {
//                          |    "LifecycleActionToken":"87654321-4321-4321-4321-210987654321",
//                          |    "AutoScalingGroupName":"my-asg",
//                          |    "LifecycleHookName":"my-lifecycle-hook",
//                          |    "EC2InstanceId":"i-1234567890abcdef0",
//                          |    "LifecycleTransition":"autoscaling:EC2_INSTANCE_TERMINATING",
//                          |    "NotificationMetadata":"additional-info"
//                          |  }
//                          |}""".stripMargin
//      val result = io.circe.parser.parse(exampleJson).flatMap(_.as[LifecycleMessage])
//      result must beRight(LifecycleMessage(0, "12345678-1234-1234-1234-123456789012",
//        "EC2 Instance-terminate Lifecycle Action", "aws.autoscaling", "123456789012",
//        ZonedDateTime.parse("2017-01-02T13:14:15Z"),
//        Regions.US_WEST_2, Seq("auto-scaling-group-arn"), Some(LifecycleDetails(
//          "87654321-4321-4321-4321-210987654321","my-asg","my-lifecycle-hook",
//          Some("i-1234567890abcdef0"),"autoscaling:EC2_INSTANCE_TERMINATING",Some("additional-info")
//        ))
//      ))
//    }
//  }
//
//  "encode example json" in {
//    val result = LifecycleMessage(0, "12345678-1234-1234-1234-123456789012",
//      "EC2 Instance-terminate Lifecycle Action", "aws.autoscaling", "123456789012",
//      ZonedDateTime.parse("2017-01-02T13:14:15Z"),
//      Regions.US_WEST_2, Seq("auto-scaling-group-arn"), Some(LifecycleDetails(
//        "87654321-4321-4321-4321-210987654321","my-asg","my-lifecycle-hook",
//        Some("i-1234567890abcdef0"),"autoscaling:EC2_INSTANCE_TERMINATING",Some("additional-info")
//      ))
//    ).asJson
//
//    result.toString().length must beGreaterThan(10) //just checking it doesn't crash really
//  }
}
