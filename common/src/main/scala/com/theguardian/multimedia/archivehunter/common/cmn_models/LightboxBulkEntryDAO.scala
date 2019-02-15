package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.{ScanamoAlpakka, Table}
import com.theguardian.multimedia.archivehunter.common.{ArchiveHunterConfiguration, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_helpers.ZonedTimeFormat
import javax.inject.Inject

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LightboxBulkEntryDAO @Inject() (config:ArchiveHunterConfiguration, ddbClientMgr:DynamoClientManager)(implicit system:ActorSystem)
  extends ZonedDateTimeEncoder with ZonedTimeFormat {
  import com.gu.scanamo.syntax._

  private implicit val mat:Materializer = ActorMaterializer.create(system)

  private val client = ddbClientMgr.getNewAlpakkaDynamoClient(config.getOptional[String]("externalData.awsProfile"))
  private val tableName = config.get[String]("lightbox.bulkTableName")

  private val table = Table[LightboxBulkEntry](tableName)
  private val descIndex = table.index("DescIndex")

  def put(entry:LightboxBulkEntry) = {
    ScanamoAlpakka.exec(client)(table.put(entry))
  }

  def entriesForUser(userEmail:String) = ScanamoAlpakka.exec(client)(table.scan).map(_.collect {
    case l @ Left(_) => l
    case r @ Right(entry) if entry.userEmail==userEmail => r
  })

  def entryForId(id:UUID) = {
    ScanamoAlpakka.exec(client)(table.get('id->id.toString))
  }

  /**
    * get an entry for the given user email matching the given description
    * @param userEmail
    * @param description
    * @return
    */
  def entryForDescAndUser(userEmail:String, description:String) = {
    ScanamoAlpakka.exec(client)(descIndex.query('description->description and ('userEmail->userEmail)))
  }.map(resultList=>{
    //should only be one failure
    val failures = resultList.collect({case Left(err)=>err})
    if(failures.nonEmpty){
      Left(failures.head)
    } else {
      Right(resultList.collectFirst({case Right(value)=>value}))
    }
  })

  def delete(entryId:String) =
    ScanamoAlpakka.exec(client)(table.delete('id->entryId))
}
