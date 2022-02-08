package models

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import org.scanamo._
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import play.api.Configuration
import org.scanamo.syntax._
import org.scanamo.generic.auto._
import org.slf4j.LoggerFactory

import java.time.{Duration, ZonedDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OAuthTokenEntryDAO @Inject() (config:Configuration, dynamoClientManager: DynamoClientManager)(implicit actorSystem:ActorSystem, mat:Materializer) {
  private val logger = LoggerFactory.getLogger(getClass)
  private lazy val scanamoAlpakka = ScanamoAlpakka(
    dynamoClientManager.getNewAsyncDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  )

  private val tableName = config.getOptional[String]("oAuth.oAuthTokensTable").getOrElse("oauth-tokens-table")
  private val table = Table[OAuthTokenEntry](tableName)
  implicit val ec:ExecutionContext = actorSystem.dispatcher

  type TokenReturnValue = List[Either[DynamoReadError, OAuthTokenEntry]]
  private val MakeOAuthTokenEntrySink = Sink.fold[TokenReturnValue, TokenReturnValue](List())(_ ++ _)

  def lookupToken(forUser:String): Future[Option[OAuthTokenEntry]] = {
    scanamoAlpakka.exec(table.query("userEmail" === forUser)).runWith(MakeOAuthTokenEntrySink)
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
    scanamoAlpakka
      .exec(table.delete("userEmail"===token.userEmail and "issued"===token.issued))
      .runWith(Sink.head)

  /**
    * Makes a new token object from the provided data and saves it to the database.
    * @param forUser user that owns the token
    * @param issuedAt timestamp of issue
    * @param value token value
    * @return a Future with the OAuthTokenEntry object. The future fails on error.
    */
  def saveToken(forUser:String, issuedAt:ZonedDateTime, value:String) = {
    val newEntry = OAuthTokenEntry(forUser, issuedAt.toInstant.getEpochSecond, value)
    scanamoAlpakka.exec(table.put(newEntry)).runWith(Sink.head).map(_=>newEntry)
  }
}
