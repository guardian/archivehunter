package requests

import java.time.ZonedDateTime

import org.scanamo.DynamoReadError
import com.theguardian.multimedia.archivehunter.common.cmn_models.{JobModel, JobModelDAO, JobStatus}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

case class JobSearchRequest (sourceId: Option[String], jobStatus: Option[JobStatus.Value],
                             jobType:Option[String], startingTime:Option[ZonedDateTime], endingTime:Option[ZonedDateTime]) {
  private val logger = Logger(getClass)

  /**
    * recursively intersects the provided job sets (i.e., return a set that contains only JobModel instances that exist in
    * ALL incoming sets
    * @param currentIntersection current state of the intersection
    * @param entry next sequence to add to
    * @param tail remainder of sets to check. When this is empty the method returns
    * @return sequence of JobModel that exist in _all_ of the
    */
  protected def makeIntersection(currentIntersection:Seq[JobModel], entry:Seq[JobModel], tail:Seq[List[JobModel]]):Seq[JobModel] = {
    val updatedIntersection = currentIntersection.intersect(entry)
    logger.debug(s"currentIntersection: $currentIntersection")
    logger.debug(s"entry: $entry")
    logger.debug(s"updatedIntersection: $updatedIntersection")
    if(tail.nonEmpty){
      makeIntersection(updatedIntersection, tail.head, tail.tail)
    } else {
      updatedIntersection
    }
  }

  /**
    * gathers all failures from any result set into a single list
    * @param sets sequence of result sets
    * @return a sequence of all errors from any of the sets
    */
  protected def gatherFailures(sets: Seq[List[Either[DynamoReadError, JobModel]]]) =
    sets.map(set=>{
      set.collect({case Left(err)=>err})
    }).filter(_.nonEmpty).foldLeft(Seq[DynamoReadError]())((errorList, newErrors)=>errorList ++ newErrors)


  /**
    * gather all successful results into a list of results from each set (i.e., strip the possibility of errors out)
    * @param sets sequence of result sets
    * @return a sequence of lists of JobModel
    */
  protected def gatherSuccess(sets: Seq[List[Either[DynamoReadError, JobModel]]]): Seq[List[JobModel]] =
    sets.map(set=>{
      set.collect({case Right(model)=>model})
    }).filter(_.nonEmpty)

  def applyLimit(seq:Seq[JobModel], limit:Int) = {
    if(seq.length>limit)
      seq.slice(0, limit)
    else
      seq
  }

  /**
    * run the search against the (implicitly) provided jobModelDAO
    * @param jobModelDAO Data Access Object instnace for JobModel - implicitly provided
    * @param ec implicitly provided execution context
    * @return a Future, containing either a list of errors or (if no errors occurred) a list of JobModels that match _all_ incoming criteria
    */
  def runSearch(overallLimit:Int=10)(implicit jobModelDAO:JobModelDAO, ec: ExecutionContext):Future[Either[Seq[DynamoReadError],Seq[JobModel]]] = {
    val searchesList = Seq(
      sourceId.map(src=>jobModelDAO.jobsForSource(src, startingTime, endingTime, limit=100000)),
      jobStatus.map(status=>jobModelDAO.jobsForStatus(status, startingTime, endingTime, limit=100000)),
      jobType.map(jt=>jobModelDAO.jobsForType(jt, startingTime, endingTime, limit=100000))
    ).collect({case Some(future)=>future})

    if(searchesList.nonEmpty){
      Future.sequence(searchesList).map(searchResults=>{
        val failedSets = gatherFailures(searchResults)

        logger.debug("Result sets:")
        searchResults.foreach(set=>logger.debug(set.toString))
        if(failedSets.nonEmpty){
          Left(failedSets)
        } else {
          val dataSets = gatherSuccess(searchResults).filter(_.nonEmpty)
          if(dataSets.isEmpty){
            Right(Seq())
          } else if(dataSets.length==1){
            Right(applyLimit(dataSets.head, overallLimit))
          } else {
            Right(applyLimit(makeIntersection(dataSets.head, dataSets(1), dataSets.tail.tail), overallLimit))
          }
        }
      })
    } else {
      Future(Right(Seq()))
    }
  }
}
