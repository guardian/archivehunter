package helpers

import com.theguardian.multimedia.archivehunter.common.cmn_models.{ScanTarget, ScanTargetDAO}
import play.api.mvc.Result
import play.api.mvc.Results._
import io.circe.syntax._
import io.circe.generic.auto._
import org.slf4j.LoggerFactory
import play.api.libs.circe.Circe
import responses.GenericErrorResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait WithScanTarget extends Circe {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * execute the provided body with a looked-up ScanTarget.
    * automatically return an error if the ScanTarget cannot be found.
    * @param collectionName bucket to look up
    * @param block function that takes a ScanTarget instance and returns an HTTP result
    */
  def withScanTargetAsync(collectionName:String, scanTargetDAO:ScanTargetDAO)(block: ScanTarget=>Future[Result]):Future[Result] = scanTargetDAO.targetForBucket(collectionName).flatMap({
    case Some(Left(err)) =>
      logger.error(s"Could not verify bucket name $collectionName: $err")
      Future(InternalServerError(GenericErrorResponse("db_error", err.toString).asJson))
    case None =>
      logger.error(s"Bucket $collectionName is not managed by us")
      Future(BadRequest(GenericErrorResponse("not_registered", s"$collectionName is not a registered collection").asJson))
    case Some(Right(target)) =>
      block(target)
  })

  def withScanTarget(collectionName:String, scanTargetDAO: ScanTargetDAO)(block: ScanTarget=>Result):Future[Result] =
    withScanTargetAsync(collectionName, scanTargetDAO){ target=> Future(block(target)) }

}
