import java.time.{ZoneId, ZonedDateTime}
import akka.NotUsed
import akka.actor.Props
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobModelDAO, JobStatus, SourceType}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.Configuration
import services.JobPurgerActor
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import org.scanamo.DynamoReadError
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, ScanResponse}

import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class JobPurgerActorSpec extends Specification with Mockito {
  sequential

  def makeStringAttribute(value:String) = AttributeValue.builder().s(value).build()
  def makeStringAttributeNull = AttributeValue.builder().nul(true).build()
  
  "JobPurgerActor!CheckMaybePurge" should {
    "delete an item that is earlier than purgeTime" in new AkkaTestkitSpecs2Support {
      implicit val ec:ExecutionContext = system.dispatcher
      val testConfig = Configuration.empty
      val mockedDDBClientManager = mock[DynamoClientManager]
      val mockedJobModelDAO = mock[JobModelDAO]
      mockedJobModelDAO.deleteJob(anyString) returns
        Future( () )

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val fakeDateTime = ZonedDateTime.of(2018,12,10,0,0,0,0,ZoneId.systemDefault())
      val testEntry = JobModel("testId",
        "proxy",
        Some(fakeDateTime),
        None,
        JobStatus.ST_PENDING,
        None,
        "fakeSourceId",
        None,
        SourceType.SRC_MEDIA,
        None
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
      mockedJobModelDAO.deleteJob(anyString) returns Future( () )

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val fakeDateTime = ZonedDateTime.of(2018,12,12,0,0,0,0,ZoneId.systemDefault())
      val testEntry = JobModel("testId",
        "proxy",
        Some(fakeDateTime),
        None,
        JobStatus.ST_PENDING,
        None,
        "fakeSourceId",
        None,
        SourceType.SRC_MEDIA,
        None
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
        Future( () )

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val fakeDateTime = ZonedDateTime.of(2018,12,12,0,0,0,0,ZoneId.systemDefault())
      val testEntry = JobModel("testId",
        "proxy",
        Some(fakeDateTime),
        None,
        JobStatus.ST_PENDING,
        None,
        "fakeSourceId",
        None,
        SourceType.SRC_MEDIA,
        None
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
        Future( () )

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val testEntry = JobModel("testId",
        "proxy",
        None,
        None,
        JobStatus.ST_PENDING,
        None,
        "fakeSourceId",
        None,
        SourceType.SRC_MEDIA,
        None
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
        Future( () )

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO)))

      val testEntry = JobModel("testId",
        "proxy",
        None,
        None,
        JobStatus.ST_PENDING,
        None,
        "fakeSourceId",
        None,
        SourceType.SRC_MEDIA,
        None
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

      def makeMockJobModel(name:String):JobModel = {
        val m = mock[JobModel]
        m.jobId returns name
        m
      }
//      val scanResultList = List(
//        ScanResponse.builder().items(
//          Map("jobId"->makeStringAttribute("testId1")).asJava,
//          Map("jobId"->makeStringAttribute("testId2")).asJava,
//          Map("jobId"->makeStringAttribute("testId3")).asJava,
//        ).build(),
//        ScanResponse.builder().items(
//          Map("jobId"->makeStringAttribute("testId4")).asJava,
//          Map("jobId"->makeStringAttribute("testId5")).asJava,
//          Map("jobId"->makeStringAttribute("testId6")).asJava,
//        ).build(),
//      )

      val scanResultList:List[List[Either[DynamoReadError, JobModel]]] = List(
        List(
          Right(makeMockJobModel("testId1")),
          Right(makeMockJobModel("testId2")),
          Right(makeMockJobModel("testId3")),
        ),
        List(
          Right(makeMockJobModel("testId4")),
          Right(makeMockJobModel("testId5")),
          Right(makeMockJobModel("testId6")),
        )
      )
      val testConfig = Configuration.from(Map("externalData.jobTable"->"testJobTable"))
      val mockedDDBClientManager = mock[DynamoClientManager]
//      val mockedDDBClient = mock[DynamoClient]
//      mockedDDBClientManager.getNewAlpakkaDynamoClient(any)(any, any) returns mockedDDBClient
//      mockedDDBClient.source(any) returns Source(scanResultList).asInstanceOf[Source[Nothing,NotUsed]]
      val mockedJobModelDAO = mock[JobModelDAO]

      val testProbe = TestProbe()

      val toTest = system.actorOf(Props(new JobPurgerActor(testConfig, mockedDDBClientManager, mockedJobModelDAO) {
        override protected val purgerRef = testProbe.ref

        override protected def makeScanSource(): Source[List[Either[DynamoReadError, JobModel]], NotUsed] = Source(scanResultList)
      }))

      Await.ready(toTest ? JobPurgerActor.StartJobPurge, 30 seconds)
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList.head.head.toOption.get, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList.head(1).toOption.get, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList.head(2).toOption.get, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList(1).head.toOption.get, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList(1)(1).toOption.get, None))
      testProbe.expectMsg(JobPurgerActor.CheckMaybePurge(scanResultList(1)(2).toOption.get, None))
    }
  }
}
