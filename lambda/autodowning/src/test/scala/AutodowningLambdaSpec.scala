import com.amazonaws.services.ec2.model.{Instance, InstanceNetworkInterface, Tag, TagDescription}
import com.google.inject.AbstractModule
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import models.{InstanceIp, LifecycleDetails}
import org.specs2.mock.Mockito
import org.specs2.mutable._
import org.mockito.Mockito.{times, verify}
import org.scanamo.DynamoReadError

import scala.util.{Failure, Success, Try}

class AutodowningLambdaSpec extends Specification with Mockito {
  class TestInjectorModule extends AbstractModule {
    override def configure(): Unit = {
      bind(classOf[ArchiveHunterConfiguration]).to(classOf[ArchiveHunterConfigurationMock])
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

        override val akkaComms = mock[AkkaComms]
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

        override val akkaComms = mock[AkkaComms]
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

        override val akkaComms = mock[AkkaComms]

        override def getEc2Tags(instance: Instance): Seq[TagDescription] = Seq(
          new TagDescription().withKey("App").withValue("myapp"),
          new TagDescription().withKey("Stack").withValue("anotherstack"),
          new TagDescription().withKey("Stage").withValue("mystage"),
          new TagDescription().withKey("irrelevant").withValue("irrelevant"),
        )
      }
      //
      test.processInstance(fakeDetails,"running", maxRetries = 1)
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

        override val akkaComms = mock[AkkaComms]

        override def getEc2Tags(instance: Instance): Seq[TagDescription] = Seq(
          new TagDescription().withKey("App").withValue("myapp"),
          new TagDescription().withKey("Stack").withValue("anotherstack"),
          new TagDescription().withKey("Stage").withValue("mystage"),
          new TagDescription().withKey("irrelevant").withValue("irrelevant"),
        )
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

        override val akkaComms = mock[AkkaComms]
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

      val addRecordMock = mock[(InstanceIp) => Try[Unit]]

      addRecordMock.apply(any) returns Success( () )

      val test = new AutoDowningLambdaMain {
        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"

        override def addRecord(rec: InstanceIp): Try[Unit] = addRecordMock(rec)

        override val akkaComms = mock[AkkaComms]
      }

      test.registerInstanceStarted(fakeDetails, fakeInstance)
      verify(addRecordMock, times(1)).apply(InstanceIp("fake-instance-id","10.11.12.13"))
      1 mustEqual 1
    }

    "retry if dynamo returns an error" in {
      val fakeDetails = mock[LifecycleDetails]

      val fakeInstance = new Instance()
        .withNetworkInterfaces(new InstanceNetworkInterface().withPrivateIpAddress("10.11.12.13"))
        .withInstanceId("fake-instance-id")

      val addRecordMock = mock[(InstanceIp) => Try[Unit]]

      addRecordMock.apply(any) returns Failure(new RuntimeException("fake error"))

      val fakeRegisterStarted = mock[(LifecycleDetails, Instance, Int) => Unit]
      fakeRegisterStarted.apply(any, any, any) returns (())

      val test = new AutoDowningLambdaMain {
        override def getInjectorModule = new TestInjectorModule

        override def getLoadBalancerHost: String = "loadbalancer"

        override def addRecord(rec: InstanceIp): Try[Unit] = addRecordMock(rec)

        override val akkaComms = mock[AkkaComms]

        /**
          * the registerInstanceStarted implementation recourses in order to retry. We detect this and redirect to the mock
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
