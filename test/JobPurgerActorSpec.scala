import java.time.{ZoneId, ZonedDateTime}

import akka.NotUsed
import akka.actor.Props
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, ConsumedCapacity, DeleteItemResult, ScanResult}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.JobModelDAO
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.JobPurgerActor
import akka.pattern.ask
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class JobPurgerActorSpec extends Specification with Mockito {
  sequential

  "JobPurgerActor!CheckMaybePurge" should {
    "delete an item that is earlier than purgeTime" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.dispatcher
      val testConfig = Configuration.empty
      val mockedDDBClientManager = mock[DynamoClientManager]
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.deleteJob(anyString) returns
        Future(new DeleteItemResult().withConsumedCapacity(new ConsumedCapacity().withCapacityUnits(1.0)))

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val testEntry:Map[String,AttributeValue] = Map(
        "jobId"->new AttributeValue().withS("testId"),
        "jobType"->new AttributeValue().withS("proxy"),
        "startedAt"->new AttributeValue().withS("2018-12-10T00:00:00Z"),
        "completedAt"->new AttributeValue().withNULL(true),
        "jobStatus"->new AttributeValue().withS("ST_PENDING"),
        "sourceId"->new AttributeValue().withS("fakeSourceId"),
        "sourceType"->new AttributeValue().withS("SRC_MEDIA")
      )

      implicit val timeout:akka.util.Timeout = 30 seconds

      Await.ready(toTest ? JobPurgerActor.CheckMaybePurge(testEntry, Some(ZonedDateTime.of(2018,12,11,0,0,0,0,ZoneId.systemDefault()))), 30 seconds)
      there was one(mockedJobModelDAO).deleteJob("testId")
    }

    "not delete an item that is later than purgeTime" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.dispatcher
      val testConfig = Configuration.empty
      val mockedDDBClientManager = mock[DynamoClientManager]
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.deleteJob(anyString) returns
        Future(new DeleteItemResult().withConsumedCapacity(new ConsumedCapacity().withCapacityUnits(1.0)))

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val testEntry:Map[String,AttributeValue] = Map(
        "jobId"->new AttributeValue().withS("testId"),
        "jobType"->new AttributeValue().withS("proxy"),
        "startedAt"->new AttributeValue().withS("2018-12-12T00:00:00Z"),
        "completedAt"->new AttributeValue().withNULL(true),
        "jobStatus"->new AttributeValue().withS("ST_PENDING"),
        "sourceId"->new AttributeValue().withS("fakeSourceId"),
        "sourceType"->new AttributeValue().withS("SRC_MEDIA")
      )

      implicit val timeout:akka.util.Timeout = 30 seconds

      Await.ready(toTest ? JobPurgerActor.CheckMaybePurge(testEntry, Some(ZonedDateTime.of(2018,12,11,0,0,0,0,ZoneId.systemDefault()))), 30 seconds)
      there was no(mockedJobModelDAO).deleteJob(anyString)
    }

    "not delete an item that is equal to purgeTime" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.dispatcher
      val testConfig = Configuration.empty
      val mockedDDBClientManager = mock[DynamoClientManager]
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.deleteJob(anyString) returns
        Future(new DeleteItemResult().withConsumedCapacity(new ConsumedCapacity().withCapacityUnits(1.0)))

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val testEntry:Map[String,AttributeValue] = Map(
        "jobId"->new AttributeValue().withS("testId"),
        "jobType"->new AttributeValue().withS("proxy"),
        "startedAt"->new AttributeValue().withS("2018-12-12T00:00:00Z"),
        "completedAt"->new AttributeValue().withNULL(true),
        "jobStatus"->new AttributeValue().withS("ST_PENDING"),
        "sourceId"->new AttributeValue().withS("fakeSourceId"),
        "sourceType"->new AttributeValue().withS("SRC_MEDIA")
      )

      implicit val timeout:akka.util.Timeout = 30 seconds

      Await.ready(toTest ? JobPurgerActor.CheckMaybePurge(testEntry, Some(ZonedDateTime.of(2018,12,12,0,0,0,0,ZoneId.of("UTC")))), 30 seconds)
      there was no(mockedJobModelDAO).deleteJob(anyString)
    }

    "not delete an item that has no start time if override is not set" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.dispatcher
      val testConfig = Configuration.empty
      val mockedDDBClientManager = mock[DynamoClientManager]
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.deleteJob(anyString) returns
        Future(new DeleteItemResult().withConsumedCapacity(new ConsumedCapacity().withCapacityUnits(1.0)))

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val testEntry:Map[String,AttributeValue] = Map(
        "jobId"->new AttributeValue().withS("testId"),
        "jobType"->new AttributeValue().withS("proxy"),
        "completedAt"->new AttributeValue().withNULL(true),
        "jobStatus"->new AttributeValue().withS("ST_PENDING"),
        "sourceId"->new AttributeValue().withS("fakeSourceId"),
        "sourceType"->new AttributeValue().withS("SRC_MEDIA")
      )

      implicit val timeout:akka.util.Timeout = 30 seconds

      Await.ready(toTest ? JobPurgerActor.CheckMaybePurge(testEntry, Some(ZonedDateTime.of(2018,12,12,0,0,0,0,ZoneId.of("UTC")))), 30 seconds)
      there was no(mockedJobModelDAO).deleteJob(anyString)
    }

    "delete an item that has no start time if override is set" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.dispatcher
      val testConfig = Configuration.from(Map("jobs.purgeInvalid"->true))
      val mockedDDBClientManager = mock[DynamoClientManager]
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.deleteJob(anyString) returns
        Future(new DeleteItemResult().withConsumedCapacity(new ConsumedCapacity().withCapacityUnits(1.0)))

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val testEntry:Map[String,AttributeValue] = Map(
        "jobId"->new AttributeValue().withS("testId"),
        "jobType"->new AttributeValue().withS("proxy"),
        "completedAt"->new AttributeValue().withNULL(true),
        "jobStatus"->new AttributeValue().withS("ST_PENDING"),
        "sourceId"->new AttributeValue().withS("fakeSourceId"),
        "sourceType"->new AttributeValue().withS("SRC_MEDIA")
      )

      implicit val timeout:akka.util.Timeout = 30 seconds

      Await.ready(toTest ? JobPurgerActor.CheckMaybePurge(testEntry, Some(ZonedDateTime.of(2018,12,12,0,0,0,0,ZoneId.of("UTC")))), 30 seconds)
      there was one(mockedJobModelDAO).deleteJob("testId")
    }
  }

  "JobPurgerActor!StartJobPurge" should {
    "initiate a stream of items from the table and cause CheckMaybePurge to be dispatched for each" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.dispatcher
      implicit val timeout:akka.util.Timeout = 30 seconds
      val scanResultList = List(
        new ScanResult().withItems(
          Map("jobId"->new AttributeValue().withS("testId1")).asJava,
          Map("jobId"->new AttributeValue().withS("testId2")).asJava,
          Map("jobId"->new AttributeValue().withS("testId3")).asJava,
        ),
        new ScanResult().withItems(
          Map("jobId"->new AttributeValue().withS("testId4")).asJava,
          Map("jobId"->new AttributeValue().withS("testId5")).asJava,
          Map("jobId"->new AttributeValue().withS("testId6")).asJava,
        ),
      )

      val testConfig = Configuration.from(Map("externalData.jobTable"->"testJobTable"))
      val mockedDDBClientManager = mock[DynamoClientManager]
      val mockedDDBClient = mock[DynamoClient]
      mockedDDBClientManager.getNewAlpakkaDynamoClient(any)(any, any) returns mockedDDBClient
      mockedDDBClient.source(any) returns Source(scanResultList).asInstanceOf[Source[Nothing,NotUsed]]
      val mockedJobModelDAO = mock[JobModelDAO]

      val testProbe = TestProbe()

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO) {
        override protected val purgerRef = testProbe.ref
      }))

      Await.ready(toTest ? JobPurgerActor.StartJobPurge, 30 seconds)
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList.head.getItems.get(0).asScala.toMap, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList.head.getItems.get(1).asScala.toMap, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList.head.getItems.get(2).asScala.toMap, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList(1).getItems.get(0).asScala.toMap, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList(1).getItems.get(1).asScala.toMap, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList(1).getItems.get(2).asScala.toMap, None))
    }
  }
}
