package helpers.LightboxStreamComponents

import java.time.ZonedDateTime

import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, QueryRequest, ScanRequest}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, RestoreStatus}
import javax.inject.Inject
import play.api.{Configuration, Logger}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class LightboxDynamoSource(memberOfBulk:String, config:Configuration, dynamoClientManager: DynamoClientManager) extends GraphStage[SourceShape[LightboxEntry]] {
  private final val out:Outlet[LightboxEntry] = Outlet("LightboxDynamoSource.out")
  val pageSize = 100

  override def shape: SourceShape[LightboxEntry] = SourceShape.of(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    protected val client = dynamoClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))
    val logger = Logger(getClass)
    private var lastEvaluatedKey:Option[mutable.Map[String, AttributeValue]] = None

    private var resultCache:Seq[mutable.Map[String,AttributeValue]] = Seq()
    private var lastPage = false

    def getNextPage(limit:Int,exclusiveStartKey:Option[mutable.Map[String, AttributeValue]]) = {
      logger.info(s"getNextPage: memberOfBulk is $memberOfBulk")
      val baseRq = new QueryRequest()
        .withTableName(config.get[String]("lightbox.tableName"))
        .withIndexName("memberOfBulkIndex")
        .withLimit(limit)
        .withKeyConditionExpression(s"memberOfBulk = :bulkId")
        .withExpressionAttributeValues(Map(":bulkId"->new AttributeValue().withS(memberOfBulk)).asJava)

      val rqWithStart = exclusiveStartKey match {
        case Some(key)=>baseRq.withExclusiveStartKey(key.asJava)
        case None=>baseRq
      }

      Try { client.query(rqWithStart) }
    }

    def optionalString(sourceData:mutable.Map[String, AttributeValue], key:String):Option[String] = {
      sourceData.get(key).flatMap(attributeValue=>Option(attributeValue.getS))
    }

    def buildLightboxEntry(sourceData: mutable.Map[String,AttributeValue]):Try[LightboxEntry] = Try {
      LightboxEntry(
        sourceData("userEmail").getS,
        sourceData("fileId").getS,
        ZonedDateTime.parse(sourceData("addedAt").getS),
        RestoreStatus.withName(sourceData("restoreStatus").getS),
        optionalString(sourceData,"restoreStarted").map(timeStr=>ZonedDateTime.parse(timeStr)),
        optionalString(sourceData,"restoreCompleted").map(timeStr=>ZonedDateTime.parse(timeStr)),
        optionalString(sourceData,"availableUntil").map(timeStr=>ZonedDateTime.parse(timeStr)),
        optionalString(sourceData,"lastError"),
        optionalString(sourceData,"memberOfBulk")
      )
    }

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(resultCache.isEmpty && !lastPage){  //if we are empty, then grab the next page of results
          logger.info("cache is empty, getting next page of 100 results")
          getNextPage(pageSize, lastEvaluatedKey) match {
            case Success(scanResult)=>
              logger.info(s"Got scan result with ${scanResult.getCount} items")
              lastEvaluatedKey = Option(scanResult.getLastEvaluatedKey.asScala)
              resultCache = scanResult.getItems.asScala.map(_.asScala)
              if(scanResult.getCount<pageSize) {
                logger.info(s"${scanResult.getCount} items is less than page size of 100, assuming that all items have been returned")
                lastPage = true
              }
            case Failure(err)=>
              logger.error(s"Could not scan Dynamodb table: ", err)
              throw err
          }
        }

        if(resultCache.isEmpty ){  //if we are still empty here then no items were added.
          logger.info(s"No more results were returned")
          complete(out)
        } else {
          buildLightboxEntry(resultCache.head) match {
            case Success(entry) =>
              push(out, entry)
              resultCache = resultCache.tail
            case Failure(err) =>
              logger.error(s"Could not marshal lightbox entry data into domain object", err)
              throw err
          } //match
        } //if(resultCache.isEmpty)
      } //onPull
    }) //setHandler
  } //new GraphStageLogic
}
