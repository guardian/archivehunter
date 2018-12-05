import com.amazonaws.services.ec2.model.{Instance, InstanceNetworkInterface, Tag}
import com.google.inject.AbstractModule
import com.gu.scanamo.error.DynamoReadError
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import models.{InstanceIp, LifecycleDetails}
import org.specs2.mock.Mockito
import org.specs2.mutable._
import org.mockito.Mockito.{times, verify}

import scala.util.{Failure, Success, Try}

class AutodowningLambdaSpec extends Specification with Mockito {
  class TestInjectorModule extends AbstractModule {
    override def configure(): Unit = {
      bind(classOf[ArchiveHunterConfiguration]).to(classOf[ArchiveHunterConfigurationMock])
    }
  }

  "AutoDowningLambdaMain.shouldHandle" should {
    "return true if all required tags are matched" in {
      val fakeTags = Map(
        "APP_TAG"->"myapp",
        "STACK_TAG"->"mystack",
        "STAGE_TAG"->"mystage"
      )

      val test = new AutoDowningLambdaMain {
        override def getTagConfigValue(configKey: String): Option[String] = fakeTags.get(configKey)

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"
      }

      val fakeInstance = new Instance().withTags(
        new Tag().withKey("App").withValue("myapp"),
        new Tag().withKey("Stack").withValue("mystack"),
        new Tag().withKey("Stage").withValue("mystage"),
        new Tag().withKey("irrelevant").withValue("irrelevant")
      )

      test.shouldHandle(fakeInstance) must beTrue
    }

    "return false if any required tags are not matched" in {
      val fakeTags = Map(
        "APP_TAG"->"myapp",
        "STACK_TAG"->"mystack",
        "STAGE_TAG"->"mystage"
      )

      val test = new AutoDowningLambdaMain {
        override def getTagConfigValue(configKey: String): Option[String] = fakeTags.get(configKey)

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"
      }

      val fakeInstance = new Instance().withTags(
        new Tag().withKey("App").withValue("myapp"),
        new Tag().withKey("Stack").withValue("anotherstack"),
        new Tag().withKey("Stage").withValue("mystage"),
        new Tag().withKey("irrelevant").withValue("irrelevant")
      )

      test.shouldHandle(fakeInstance) must beFalse
    }

    "not crash if any required tags are missing" in {
      val fakeTags = Map(
        "APP_TAG"->"myapp",
        "STACK_TAG"->"mystack",
        "STAGE_TAG"->"mystage"
      )

      val test = new AutoDowningLambdaMain {
        override def getTagConfigValue(configKey: String): Option[String] = fakeTags.get(configKey)

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"
      }

      val fakeInstance = new Instance().withTags(
        new Tag().withKey("App").withValue("myapp"),
        new Tag().withKey("Stage").withValue("mystage"),
        new Tag().withKey("irrelevant").withValue("irrelevant")
      )

      test.shouldHandle(fakeInstance) must beFalse
    }
  }

  "AutodowningLambdaMain.processInstance" should {
    "raise an exception if getEc2Info consistently fails" in {
      val fakeDetails = mock[LifecycleDetails]
      fakeDetails.EC2InstanceId returns Some("my-instance-id")

      val expectedError = new RuntimeException("My hovercraft is full of eels")

      val test = new AutoDowningLambdaMain {
        override def getEc2Info(instanceId: String): Try[Option[Instance]] = Failure(expectedError)

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"
      }

      test.processInstance(fakeDetails,"starting", maxRetries = 1) must throwA(expectedError)
    }

    "call registerInstanceTerminated if we are handling this instance and it is terminating" in {
      val fakeDetails = mock[LifecycleDetails]
      fakeDetails.EC2InstanceId returns Some("my-instance-id")

      val fakeInstance = mock[Instance]

      val fakeRegisterTerminated = mock[Function2[LifecycleDetails,Int,Unit]]
      fakeRegisterTerminated.apply(any, any) returns (())

      val fakeRegisterStarted = mock[Function3[LifecycleDetails,Instance,Int,Unit]]
      fakeRegisterStarted.apply(any, any, any) returns (())

      val test = new AutoDowningLambdaMain {
        override def getEc2Info(instanceId: String): Try[Option[Instance]] = Success(Some(fakeInstance))

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"

        override def registerInstanceStarted(details: LifecycleDetails, instance:Instance, attempt: Int): Unit = fakeRegisterStarted(details, instance, attempt)

        override def registerInstanceTerminated(details: LifecycleDetails, attempt: Int): Unit = fakeRegisterTerminated(details, attempt)

        override def shouldHandle(instance: Instance): Boolean = true
      }
      //
      test.processInstance(fakeDetails,"terminated", maxRetries = 1)
      verify(fakeRegisterTerminated, times(1)).apply(fakeDetails, 0)
      verify(fakeRegisterStarted, times(0)).apply(any, any, any)
      1 mustEqual 1 //specs2 requires a simple test at the end
    }

    "call registerInstanceStarted if we are handling this instance and it is starting" in {
      val fakeDetails = mock[LifecycleDetails]
      fakeDetails.EC2InstanceId returns Some("my-instance-id")

      val fakeInstance = mock[Instance]

      val fakeRegisterTerminated = mock[Function2[LifecycleDetails,Int,Unit]]
      fakeRegisterTerminated.apply(any, any) returns (())

      val fakeRegisterStarted = mock[Function3[LifecycleDetails,Instance,Int,Unit]]
      fakeRegisterStarted.apply(any, any, any) returns (())

      val test = new AutoDowningLambdaMain {
        override def getEc2Info(instanceId: String): Try[Option[Instance]] = Success(Some(fakeInstance))

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"

        override def registerInstanceStarted(details: LifecycleDetails, instance:Instance, attempt: Int): Unit = fakeRegisterStarted(details, instance, attempt)

        override def registerInstanceTerminated(details: LifecycleDetails, attempt: Int): Unit = fakeRegisterTerminated(details, attempt)

        override def shouldHandle(instance: Instance): Boolean = true
      }
      //
      test.processInstance(fakeDetails,"started", maxRetries = 1)
      verify(fakeRegisterStarted, times(1)).apply(fakeDetails, fakeInstance, 0)
      verify(fakeRegisterTerminated, times(0)).apply(any, any)
      1 mustEqual 1 //specs2 requires a simple test at the end
    }

    "call neither registerInstanceStarted nor registerInstanceTerminated if we not handling this instance" in {
      val fakeDetails = mock[LifecycleDetails]
      fakeDetails.EC2InstanceId returns Some("my-instance-id")

      val fakeInstance = mock[Instance]

      val fakeRegisterTerminated = mock[Function2[LifecycleDetails,Int,Unit]]
      fakeRegisterTerminated.apply(any, any) returns (())

      val fakeRegisterStarted = mock[Function3[LifecycleDetails,Instance,Int,Unit]]
      fakeRegisterStarted.apply(any, any, any) returns (())

      val test = new AutoDowningLambdaMain {
        override def getEc2Info(instanceId: String): Try[Option[Instance]] = Success(Some(fakeInstance))

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"

        override def registerInstanceStarted(details: LifecycleDetails, instance:Instance, attempt: Int): Unit = fakeRegisterStarted(details, instance, attempt)

        override def registerInstanceTerminated(details: LifecycleDetails, attempt: Int): Unit = fakeRegisterTerminated(details, attempt)

        override def shouldHandle(instance: Instance): Boolean = false
      }
      //
      test.processInstance(fakeDetails,"started", maxRetries = 1)
      verify(fakeRegisterStarted, times(0)).apply(any, any, any)
      verify(fakeRegisterTerminated, times(0)).apply(any, any)
      1 mustEqual 1 //specs2 requires a simple test at the end
    }

    "throw a RuntimeException if there is no instance metadata available" in {
      val fakeDetails = mock[LifecycleDetails]
      fakeDetails.EC2InstanceId returns Some("my-instance-id")

      val expectedException = new RuntimeException(s"No details returned from EC2 for instance my-instance-id")
      val test = new AutoDowningLambdaMain {
        override def getEc2Info(instanceId: String): Try[Option[Instance]] = Success(None)

        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"
      }

      test.processInstance(fakeDetails,"starting", maxRetries = 1) must throwA(expectedException)
    }
  }

  "AutoDowningLambdaMain.registerInstanceStarted" should {
    "look up IP address information and save it to dynamo" in {
      val fakeDetails = mock[LifecycleDetails]
      val fakeInstance = new Instance()
        .withNetworkInterfaces(new InstanceNetworkInterface().withPrivateIpAddress("10.11.12.13"))
        .withInstanceId("fake-instance-id")

      val addRecordMock = mock[Function1[InstanceIp,Option[Either[DynamoReadError,InstanceIp]]]]

      addRecordMock.apply(any) returns None

      val test = new AutoDowningLambdaMain {
        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"

        override def addRecord(rec: InstanceIp): Option[Either[DynamoReadError, InstanceIp]] = addRecordMock(rec)
      }

      test.registerInstanceStarted(fakeDetails, fakeInstance)
      verify(addRecordMock, times(1)).apply(InstanceIp("fake-instance-id","10.11.12.13"))
      1 mustEqual 1
    }

    "retry if dynamo returns an error" in {
      val fakeDetails = mock[LifecycleDetails]
      val fakeError = mock[DynamoReadError]
      fakeError.toString returns "fake error"

      val fakeInstance = new Instance()
        .withNetworkInterfaces(new InstanceNetworkInterface().withPrivateIpAddress("10.11.12.13"))
        .withInstanceId("fake-instance-id")

      val addRecordMock = mock[Function1[InstanceIp,Option[Either[DynamoReadError,InstanceIp]]]]

      addRecordMock.apply(any) returns Some(Left(fakeError))

      val fakeRegisterStarted = mock[Function3[LifecycleDetails,Instance,Int,Unit]]
      fakeRegisterStarted.apply(any, any, any) returns (())

      val test = new AutoDowningLambdaMain {
        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"

        override def addRecord(rec: InstanceIp): Option[Either[DynamoReadError, InstanceIp]] = addRecordMock(rec)

        /**
          * the registerInstanceStarted implementation recurses in order to retry. We detect this and redirect to the mock
          * if it's a retry (attempt > 0)
          * @param details [[LifecycleDetails]] object from the Cloudwatch Event
          * @param instance `Instance` instance from EC2 SDK
          * @param attempt retry attempt; see `registerInstanceTerminated` for details. Leave this off when calling.
          */
        override def registerInstanceStarted(details: LifecycleDetails, instance: Instance, attempt: Int): Unit = {
          if(attempt==0)
            super.registerInstanceStarted(details, instance, attempt)
          else
            fakeRegisterStarted(details, instance, attempt)
        }
      }

      test.registerInstanceStarted(fakeDetails, fakeInstance)
      verify(addRecordMock, times(1)).apply(InstanceIp("fake-instance-id","10.11.12.13"))
      verify(fakeRegisterStarted, times(1)).apply(fakeDetails, fakeInstance, 1)
      1 mustEqual 1
    }
  }
}
