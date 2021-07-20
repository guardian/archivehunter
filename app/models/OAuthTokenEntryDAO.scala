package models

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.gu.scanamo._
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import play.api.Configuration
import com.gu.scanamo.syntax._
import com.gu.scanamo
import org.slf4j.LoggerFactory
import com.gu.scanamo.query._
import java.time.{Duration, ZonedDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OAuthTokenEntryDAO @Inject() (config:Configuration, dynamoClientManager: DynamoClientManager)(implicit actorSystem:ActorSystem, mat:Materializer) {
  private val logger = LoggerFactory.getLogger(getClass)
  private lazy val ddbClient = dynamoClientManager.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  private val tableName = config.getOptional[String]("oAuth.oAuthTokensTable").getOrElse("oauth-tokens-table")
  private val table = Table[OAuthTokenEntry](tableName)
  implicit val ec:ExecutionContext = actorSystem.dispatcher

  def lookupToken(forUser:String): Future[Option[OAuthTokenEntry]] = {
    ScanamoAlpakka.exec(ddbClient)(table.query('userEmail->forUser))
  }.flatMap(results=>{
    val failures = results.collect({case Left(err)=>err})
    if(failures.nonEmpty) {
      logger.error(s"${failures.length} operations failed when getting oauth refresh token for $forUser:")
      failures.foreach(f=>logger.error(s"\t${f.toString}"))
      Future.failed(new RuntimeException(s"${failures.length} operations failed, see logs for details"))
    } else {
      Future(results.collect({case Right(entry)=>entry}))
    }
  }).map(_.headOption)

  def removeUsedToken(token:OAuthTokenEntry) =
    ScanamoAlpakka.exec(ddbClient)(table.delete('userEmail->token.value and 'issued->token.issued))

  def saveToken(forUser:String, issuedAt:ZonedDateTime, value:String) = {
    val newEntry = OAuthTokenEntry(forUser, issuedAt.toInstant.getEpochSecond, value)
    ScanamoAlpakka.exec(ddbClient)(table.put(newEntry)).flatMap({
      case Some(Left(err)) =>
        logger.error(s"Could not save refresh token for $forUser: $err")
        Future.failed(new RuntimeException(s"Could not save refresh token for $forUser: $err"))
      case Some(Right(result)) =>
        Future(result)
      case None =>
        Future(newEntry)
    })
  }

}
