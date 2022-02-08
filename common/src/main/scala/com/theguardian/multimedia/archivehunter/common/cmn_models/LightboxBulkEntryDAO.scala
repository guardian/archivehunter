package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.util.UUID
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, Materializer}
import org.scanamo.{DynamoReadError, ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LightboxBulkEntryDAO @Inject() (config:ArchiveHunterConfiguration, ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat {
  import org.scanamo.syntax._
  import org.scanamo.generic.auto._

  private implicit val mat:Materializer = ActorMaterializer.create(system)

  private val scanamoAlpakka = ScanamoAlpakka(
    ddbClientMgr.getNewAsyncDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  )

  private val tableName = config.get[String]("lightbox.bulkTableName")

  private val table = Table[LightboxBulkEntry](tableName)
  private val descIndex = table.index("DescIndex")

  private val MakeLightboxEntrySink = Sink.fold[List[Either[DynamoReadError, LightboxBulkEntry]],List[Either[DynamoReadError, LightboxBulkEntry]]](List())(_ ++ _)
  /**
    * Save a new lightbox bulk entry into the table
    * @param entry LightboxBulkEntry to save
    * @return a Future which completes when the save is done, containing the provided entry. The future fails on error.
    */
  def put(entry:LightboxBulkEntry) = {
    scanamoAlpakka.exec(table.put(entry)).runWith(Sink.head).map(_=>entry)
  }

  /**
    * retrieve lightbox entries for the given user
    * FIXME: this implementation is inefficient and should be replaced with an index query
    * @param userEmail email of the user to retrieve data for
    * @return a Future, containing a List of either dynamodb read errors or LightboxBulkEntries
    */
  def entriesForUser(userEmail:String) = scanamoAlpakka
    .exec(table.scan)
    .map(_.collect {
      case l @ Left(_) => l
      case r @ Right(entry) if entry.userEmail==userEmail => r
    }).runWith(MakeLightboxEntrySink)

  /**
    * retrieve the lightbox bulk entry with the given uuid
    * @param id UUID to retrieve
    * @return a Future containing:
    *         - None if there was no entry with that ID
    *         - Left with a DynamoReadError on failure
    *         - Right with a LightboxBulkEntry if we found one
    */
  def entryForId(id:UUID) = {
    scanamoAlpakka
      .exec(table.get("id"===id.toString))
      .runWith(Sink.head)
  }

  /**
    * get an entry for the given user email matching the given description
    * @param userEmail user email to search for
    * @param description description to search for
    * @return
    */
  def entryForDescAndUser(userEmail:String, description:String) = {
    scanamoAlpakka.exec(descIndex.query("description"===description and ("userEmail"===userEmail)))
  }.map(resultList=>{
    //should only be one failure
    val failures = resultList.collect({case Left(err)=>err})
    if(failures.nonEmpty){
      Left(failures.head)
    } else {
      Right(resultList.collectFirst({case Right(value)=>value}))
    }
  }).runWith(Sink.head)

  /**
    * Delete the bulk entry with the given id
    * @param entryId ID to delete
    * @return
    */
  def delete(entryId:String) =
    scanamoAlpakka
      .exec(table.delete("id"===entryId))
      .runWith(Sink.head)
}
