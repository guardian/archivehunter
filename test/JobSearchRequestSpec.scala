import com.gu.scanamo.error.{DynamoReadError, MissingProperty}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobStatus, SourceType}
import org.specs2.mock.Mockito
import org.specs2.mutable._
import requests.JobSearchRequest

class JobSearchRequestSpec extends Specification with Mockito{
  "JobSearchRequest.makeIntersection" should {
    "return a list of the jobmodel instances that exist in all sets" in {
      val sampleList = List(
        JobModel("job1","Restore",None,None,JobStatus.ST_ERROR,None,"source1",None,SourceType.SRC_MEDIA),
        JobModel("job2","Proxy",None,None,JobStatus.ST_ERROR,None,"source2",None,SourceType.SRC_MEDIA),
        JobModel("job3","Restore",None,None,JobStatus.ST_ERROR,None,"source3",None,SourceType.SRC_MEDIA),
        JobModel("job4","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source4",None,SourceType.SRC_MEDIA),
        JobModel("job5","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source5",None,SourceType.SRC_MEDIA),
        JobModel("job6","Proxy",None,None,JobStatus.ST_ERROR,None,"source6",None,SourceType.SRC_MEDIA),
      )

      val resultsSeq = Seq(
        List(sampleList.head, sampleList(2), sampleList(3), sampleList(5)),
        List(sampleList(1), sampleList(3), sampleList(5)),
        List(sampleList(2), sampleList(3), sampleList(4), sampleList(5))
      )

      val toTest = new JobSearchRequest(None,None,None,None,None) {
        def testMakeIntersection(currentIntersection:Seq[JobModel], entry:Seq[JobModel], tail:Seq[List[JobModel]]) = makeIntersection(currentIntersection, entry, tail)
      }

      toTest.testMakeIntersection(resultsSeq.head, resultsSeq(1), resultsSeq.tail.tail) mustEqual Seq(sampleList(3),sampleList(5))
    }

    "not fail if there is no list tail to check" in {
      val sampleList = List(
        JobModel("job1","Restore",None,None,JobStatus.ST_ERROR,None,"source1",None,SourceType.SRC_MEDIA),
        JobModel("job2","Proxy",None,None,JobStatus.ST_ERROR,None,"source2",None,SourceType.SRC_MEDIA),
        JobModel("job3","Restore",None,None,JobStatus.ST_ERROR,None,"source3",None,SourceType.SRC_MEDIA),
        JobModel("job4","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source4",None,SourceType.SRC_MEDIA),
        JobModel("job5","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source5",None,SourceType.SRC_MEDIA),
        JobModel("job6","Proxy",None,None,JobStatus.ST_ERROR,None,"source6",None,SourceType.SRC_MEDIA),
      )

      val resultsSeq = Seq(
        List(sampleList.head, sampleList(2), sampleList(3), sampleList(5)),
        List(sampleList(1), sampleList(3), sampleList(5))
      )

      val toTest = new JobSearchRequest(None,None,None,None,None) {
        def testMakeIntersection(currentIntersection:Seq[JobModel], entry:Seq[JobModel], tail:Seq[List[JobModel]]) = makeIntersection(currentIntersection, entry, tail)
      }

      toTest.testMakeIntersection(resultsSeq.head, resultsSeq(1), resultsSeq.tail.tail) mustEqual Seq(sampleList(3),sampleList(5))
    }
  }

  "JobSearchRequest.gatherFailures" should {
    "collect any failures spread across multiple sets into a single list" in {
      val sampleList = List(
        JobModel("job1","Restore",None,None,JobStatus.ST_ERROR,None,"source1",None,SourceType.SRC_MEDIA),
        JobModel("job2","Proxy",None,None,JobStatus.ST_ERROR,None,"source2",None,SourceType.SRC_MEDIA),
        JobModel("job3","Restore",None,None,JobStatus.ST_ERROR,None,"source3",None,SourceType.SRC_MEDIA),
        JobModel("job4","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source4",None,SourceType.SRC_MEDIA),
        JobModel("job5","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source5",None,SourceType.SRC_MEDIA),
        JobModel("job6","Proxy",None,None,JobStatus.ST_ERROR,None,"source6",None,SourceType.SRC_MEDIA),
      )

      val mixedList:Seq[List[Either[DynamoReadError, JobModel]]] = Seq(
        List(Left(MissingProperty), Right(sampleList(2)), Right(sampleList(3)), Left(MissingProperty)),
        List(Right(sampleList(4)), Left(MissingProperty), Right(sampleList(3))),
        List(Left(MissingProperty), Right(sampleList.head), Left(MissingProperty), Right(sampleList(5))),
      )

      val toTest = new JobSearchRequest(None,None,None,None,None) {
        def testGatherFailures(sets: Seq[List[Either[DynamoReadError, JobModel]]]) = gatherFailures(sets)
      }

      toTest.testGatherFailures(mixedList) mustEqual Seq(MissingProperty,MissingProperty,MissingProperty,MissingProperty,MissingProperty)
    }
  }

  "JobSearchRequest.gatherSuccess" should {
    "collect any job models spread across multiple sets into a set of lists" in {
      val sampleList = List(
        JobModel("job1","Restore",None,None,JobStatus.ST_ERROR,None,"source1",None,SourceType.SRC_MEDIA),
        JobModel("job2","Proxy",None,None,JobStatus.ST_ERROR,None,"source2",None,SourceType.SRC_MEDIA),
        JobModel("job3","Restore",None,None,JobStatus.ST_ERROR,None,"source3",None,SourceType.SRC_MEDIA),
        JobModel("job4","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source4",None,SourceType.SRC_MEDIA),
        JobModel("job5","Thumbnail",None,None,JobStatus.ST_ERROR,None,"source5",None,SourceType.SRC_MEDIA),
        JobModel("job6","Proxy",None,None,JobStatus.ST_ERROR,None,"source6",None,SourceType.SRC_MEDIA),
      )

      val mixedList:Seq[List[Either[DynamoReadError, JobModel]]] = Seq(
        List(Left(MissingProperty), Right(sampleList(2)), Right(sampleList(3)), Left(MissingProperty)),
        List(Right(sampleList(4)), Left(MissingProperty), Right(sampleList(3))),
        List(Left(MissingProperty), Right(sampleList.head), Left(MissingProperty), Right(sampleList(5))),
      )

      val toTest = new JobSearchRequest(None,None,None,None,None) {
        def testGatherSuccess(sets: Seq[List[Either[DynamoReadError, JobModel]]]) = gatherSuccess(sets)
      }

      toTest.testGatherSuccess(mixedList) mustEqual Seq(
          List(sampleList(2),sampleList(3)),
          List(sampleList(4), sampleList(3)),
          List(sampleList.head, sampleList(5))
        )

    }
  }
}
