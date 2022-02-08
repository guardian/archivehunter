package services

import java.util.UUID
import akka.actor.{Actor, ActorRef, Timers}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager

import javax.inject.{Inject, Singleton}
import play.api.Logger
import software.amazon.awssdk.services.dynamodb.model.{DescribeTableRequest, GlobalSecondaryIndexUpdate, ProvisionedThroughput, TableDescription, TableStatus, UpdateGlobalSecondaryIndexAction, UpdateTableRequest}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

object DynamoCapacityActor {
  trait DCAMsg {
    val tableName:String
    val id:Option[UUID]
  }

  trait DCAReply {
  }

  /**
    * this message is dispatched by a timer, to check if any tables in the "to check" list have become active again
    */
  case object TimedStateCheck

  case class UpdateCapacityTable(tableName:String, readTarget:Option[Int], writeTarget:Option[Int], indexUpdates:Seq[UpdateCapacityIndex], signalActor: ActorRef, signalMsg:AnyRef, id:Option[UUID]=None) extends DCAMsg
  case class UpdateCapacityIndex(indexName:String, readTarget:Option[Int], writeTarget:Option[Int])

  case class UpdateCapacity(ops: Seq[DCAMsg], signalActor: ActorRef, signalMsg:AnyRef)

  case class PendingUpdate(tableName:String, indexName:Option[String], readTarget:Int, writeTarget:Int)

  /**
    * add the given request to the internal list, for testing
    * @param rq request to add
    */
  case class TestAddRequest(rq:DCAMsg)
  case object TestGetCheckList

  /**
    * response message if the table state is not active
    * @param tableName table name that can't update
    * @param actualState actual state it's in
    * @param wantedState the state it must be in before we can start
    */
  case class TableWrongStateError(tableName:String, actualState:String, wantedState: String)

  /**
    * response message if the index updates can't be corralled (e.g., index name does not exist)
    * @param tableName table name that can update
    * @param problems Sequence of Throwables that describe the problems
    */
  case class InvalidRequestError(tableName:String, problems:Seq[Throwable])

  /**
    * response message if the operation succeeded
    * @param mustWait boolean indicating whether provisioned capacity was already met, so we can start straightaway
    */
  case class UpdateRequestSuccess(tableName:String, mustWait:Boolean)

  /**
    * response message for [[TestGetCheckList]]
    * @param entries current entries in the queue
    */
  case class TestCheckListResponse(entries:Seq[DCAMsg])
}

@Singleton
class DynamoCapacityActor @Inject() (ddbClientMgr:DynamoClientManager, config:ArchiveHunterConfiguration) extends Actor {
  import DynamoCapacityActor._
  private val logger = Logger(getClass)

  private var checkList:Seq[DCAMsg] = Seq()
  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val ddbClient = ddbClientMgr.getClient(awsProfile)

  /**
    * converts an [[UpdateCapacityIndex]] request into a DynamoDB [[GlobalSecondaryIndexUpdate]] request.
    *
    * @param rq [[UpdateCapacityIndex]] instance describing the update to make
    * @param desc DDB TableDescription instance describing the table that the update will take place on
    * @return if there is an error then a Failure is returned; if the provisioned capacity is already correct then Success(None) is
    *         returned; if an update is required then Success(Some(GlobalSecondaryIndexUpdate())) is returned
    */
  def updateForIndex(rq:UpdateCapacityIndex, desc:TableDescription):Try[Option[GlobalSecondaryIndexUpdate]] = {
    val indexDesc = desc.globalSecondaryIndexes().asScala.find(_.indexName()==rq.indexName) match {
      case None=>
        return Failure(new RuntimeException(s"Could not find index ${rq.indexName} on table ${desc.tableName()}"))
      case Some(idx)=>idx
    }
    val currentThroughput = indexDesc.provisionedThroughput()

    val actualReadTarget = rq.readTarget match {
      case None=>currentThroughput.readCapacityUnits().toLong
      case Some(tgt)=>tgt.toLong
    }

    val actualWriteTarget = rq.writeTarget match {
      case None=>currentThroughput.writeCapacityUnits().toLong
      case Some(tgt)=>tgt.toLong
    }

    if(currentThroughput.readCapacityUnits()==actualReadTarget && currentThroughput.writeCapacityUnits()==actualWriteTarget){
      Success(None)
    } else {
      Success(Some(new GlobalSecondaryIndexUpdate().toBuilder.update(
        new UpdateGlobalSecondaryIndexAction().toBuilder
          .indexName(rq.indexName)
          .provisionedThroughput(new ProvisionedThroughput().toBuilder
            .readCapacityUnits(actualReadTarget)
            .writeCapacityUnits(actualWriteTarget)
            .build()
          )
          .build()
        ).build()
      ))
    }
  }

  private def makeDescribeTableRequest(tableName:String) =
    new DescribeTableRequest().toBuilder.tableName(tableName).build()

  def getTableStatus(tableName: String):String =
    ddbClient.describeTable(makeDescribeTableRequest(tableName)).table().tableStatusAsString()

  /**
    * recursively check the state of tables that need to be updated and dispatch the requested message to the requested
    * actor if they have re-entered Active.
    * @param toCheck seq of tables to check
    * @param notReady seq of tables that are not ready yet. Used for recursion, don't specify when calling
    * @return seq of tables that are not yet ready
    */
  def checkAndDispatch(toCheck:Seq[DCAMsg], notReady:Seq[DCAMsg]=Seq()):Seq[DCAMsg] = {
    toCheck.headOption match {
      case Some(msg:UpdateCapacityTable) =>
        logger.debug(s"Checking table ${msg.tableName}")
        val st = getTableStatus(msg.tableName)
        if (st == "ACTIVE") {
          logger.debug(s"Table ${msg.tableName} has re-entered ACTIVE state, notifying")
          msg.signalActor ! msg.signalMsg
          checkAndDispatch(toCheck.tail, notReady)
        } else {
          logger.debug(s"Table ${msg.tableName} is in $st state")
          checkAndDispatch(toCheck.tail, notReady ++ Seq(msg))
        }
      case Some(msg:UpdateCapacityIndex)=>
        throw new RuntimeException("UpdateCapacityIndex is not implemented yet")
      case Some(otherMsg)=>
        throw new RuntimeException(s"$otherMsg is not implemented")
      case None=>
        notReady
    }
  }

  override def receive: Receive = {
    case TimedStateCheck=>
      logger.debug("got TimedStateCheck")
      checkList = checkAndDispatch(checkList)
    case TestAddRequest(toAdd)=>
      checkList = checkList ++ Seq(toAdd)
    case TestGetCheckList=>
      sender() ! TestCheckListResponse(checkList)
    case tableRq: UpdateCapacityTable=>
      val result = ddbClient.describeTable(makeDescribeTableRequest(tableRq.tableName))
      if(result.table().tableStatus()!=TableStatus.ACTIVE){
        logger.warn(s"Can't update table status while it is in ${result.table().tableStatusAsString()} state.")
        sender() ! TableWrongStateError(tableRq.tableName, result.table().tableStatusAsString(), "ACTIVE")
      } else {
        val tableThroughput = result.table().provisionedThroughput()

        val potentialIndexUpdate = tableRq.indexUpdates.map(updateForIndex(_, result.table()))
        val potentialIndexUpdateFailures = potentialIndexUpdate.collect({case Failure(err)=>err})
        if(potentialIndexUpdateFailures.nonEmpty){
          logger.error("Could not build list of index updates:")
          potentialIndexUpdateFailures.foreach(err=>logger.error("Index update list error: ", err))
          sender() ! InvalidRequestError(tableRq.tableName, potentialIndexUpdateFailures)
        } else {
          val indexUpdates = potentialIndexUpdate.collect({case Success(Some(update))=>update})
          val actualReadTarget = tableRq.readTarget match {
            case None=>tableThroughput.readCapacityUnits().toLong
            case Some(target)=>target.toLong
          }

          val actualWriteTarget = tableRq.writeTarget match {
            case None=>tableThroughput.writeCapacityUnits().toLong
            case Some(target)=>target.toLong
          }

          val rq = new UpdateTableRequest().toBuilder.tableName(tableRq.tableName)

          if(tableThroughput.readCapacityUnits()==0 && tableThroughput.writeCapacityUnits()==0){
            logger.info(s"Table ${tableRq.tableName} is in auto-provisioning mode, don't need to update.")
            tableRq.signalActor ! tableRq.signalMsg
            sender() ! UpdateRequestSuccess(tableRq.tableName, mustWait = false)
          } else if(tableThroughput.readCapacityUnits()==actualReadTarget && tableThroughput.writeCapacityUnits()==actualWriteTarget && indexUpdates.isEmpty){
            logger.info(s"Table ${tableRq.tableName} and indices already have requested throughput")
            tableRq.signalActor ! tableRq.signalMsg
            sender() ! UpdateRequestSuccess(tableRq.tableName, mustWait = false)
          } else {
            val rqWithTableUpdate = if (tableThroughput.readCapacityUnits() != actualReadTarget || tableThroughput.writeCapacityUnits() != actualWriteTarget) {
              rq.provisionedThroughput(new ProvisionedThroughput().toBuilder
                .readCapacityUnits(actualReadTarget)
                .writeCapacityUnits(actualWriteTarget)
                .build()
              )
            } else {
              rq
            }

            val rqWithIndexUpdate = if(indexUpdates.nonEmpty){
              rq.globalSecondaryIndexUpdates(indexUpdates.asJavaCollection)
            } else {
              rq
            }

            logger.info(s"Updating table ${tableRq.tableName} with capacity $actualReadTarget read, $actualWriteTarget write and index $indexUpdates")

            val updateResult = ddbClient.updateTable(rq.build())
            checkList ++= Seq(tableRq)
            sender() ! UpdateRequestSuccess(tableRq.tableName, mustWait = true)
          }
        }
      }
  }
}
