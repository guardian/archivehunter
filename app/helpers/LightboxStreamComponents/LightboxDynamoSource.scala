package helpers.LightboxStreamComponents

import java.time.ZonedDateTime
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{AbstractOutHandler, GraphStage, GraphStageLogic}
import com.theguardian.multimedia.archivehunter.common.clientManagers.DynamoClientManager
import com.theguardian.multimedia.archivehunter.common.cmn_models.{LightboxEntry, RestoreStatus}
import play.api.{Configuration, Logger}

import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
  * this is an Akka source that yields LightboxEntries belonging to a specific bulk from a DynamoDB query.
  * It's not injectable or singleton because you need to pass it the bulk ID to query when constructing; this means that
  * you must also manually pass in the configuration and DynamoClientManager objects. Get these by injecting them into the caller.
  *
  * @param memberOfBulk the bulk entry ID to query
  * @param config app configuration as a Lightbend Configuration object. Get this from an injector.
  * @param dynamoClientManager DynamoClientManager object. Get this from an injector
  */
class LightboxDynamoSource(memberOfBulk:String, config:Configuration, dynamoClientManager: DynamoClientManager) extends GraphStage[SourceShape[LightboxEntry]] {
  private final val out:Outlet[LightboxEntry] = Outlet("LightboxDynamoSource.out")
  val pageSize = 100

  override def shape: SourceShape[LightboxEntry] = SourceShape.of(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, QueryRequest}

    protected val client = dynamoClientManager.getClient(config.getOptional[String]("externalData.awsProfile"))
    val logger = Logger(getClass)
    private var lastEvaluatedKey:Option[mutable.Map[String, AttributeValue]] = None

    private var resultCache:Seq[mutable.Map[String,AttributeValue]] = Seq()
    private var lastPage = false
    private var ctr = 0
    /**
      * retrieve next page of results from DynamoDB
      * @param limit maximum number of items to return
      * @param exclusiveStartKey key to start at. If more than `limit` items match, then the value for this is present in the previous
      *                          page's result as `Option(scanResult.getLastEvaluatedKey.asScala)`
      * @return a Try with either an AWS QueryResult object or an error
      */
    def getNextPage(limit:Int,exclusiveStartKey:Option[mutable.Map[String, AttributeValue]]) = {
      logger.info(s"getNextPage: memberOfBulk is $memberOfBulk")
      val baseRq = new QueryRequest(config.get[String]("lightbox.tableName")).toBuilder
        .indexName("memberOfBulkIndex")
        .limit(limit)
        .keyConditionExpression(s"memberOfBulk = :bulkId")
        .expressionAttributeValues(Map(":bulkId"->new AttributeValue().toBuilder.s(memberOfBulk).build()).asJava)

      val rqWithStart = exclusiveStartKey match {
        case Some(key)=>baseRq.exclusiveStartKey(key.asJava)
        case None=>baseRq
      }

      Try { client.query(rqWithStart.build()) }
    }

    /**
      * helper method to safely return an Option[String] for the given key if it may not be present
      * @param sourceData source data as returned by DynamoDB
      * @param key key to check
      * @return an Option that contains the value of the key, if one is present
      */
    def optionalString(sourceData:mutable.Map[String, AttributeValue], key:String):Option[String] = {
      sourceData.get(key).flatMap(attributeValue=>Option(attributeValue.s()))
    }

    /**
      * marshals the provided data from DynamoDB into a LightboxEntry
      * @param sourceData data as returned from DynamoDB
      * @return a Try that either contains the LightboxEntry or an error that occurred while marshalling
      */
    def buildLightboxEntry(sourceData: mutable.Map[String,AttributeValue]):Try[LightboxEntry] = Try {
      LightboxEntry(
        sourceData("userEmail").s(),
        sourceData("fileId").s(),
        ZonedDateTime.parse(sourceData("addedAt").s()),
        RestoreStatus.withName(sourceData("restoreStatus").s()),
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
              logger.info(s"Got scan result with ${scanResult.count()} items")
              lastEvaluatedKey = Option(scanResult.lastEvaluatedKey().asScala)
              resultCache = scanResult.items().asScala.map(_.asScala).toSeq

              ctr+=scanResult.count()
              logger.info(s"Scan returned ${scanResult.count()} items, running total is now $ctr items total")
              if(scanResult.count()<pageSize) {
                logger.info(s"${scanResult.count()} items is less than page size of 100, assuming that all items have been returned")
                lastPage = true
              }

            case Failure(err)=>
              logger.error(s"Could not scan Dynamodb table: ", err)
              failStage(err)
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
              failStage(err)
          } //match
        } //if(resultCache.isEmpty)
      } //onPull
    }) //setHandler
  } //new GraphStageLogic
}
