package helpers

import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, Condition, QueryRequest, QueryResult}

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * having versioning problems getting alpakka streaming to work so put together own stream source.
  * this performs a paginating query on the supplied dynamo table and yields results as a Map[String,AttributeValue].
  * use a downstream converter to turn that into something useful.
  * @param dynamoClient
  * @param tableName
  * @param indexName
  * @param keyConditions
  */
class DynamoQuerySource (dynamoClient:AmazonDynamoDB, tableName:String, indexName:Option[String], keyConditions:Map[String,Condition])
  extends GraphStage[SourceShape[Map[String,AttributeValue]]]
{
  final val out:Outlet[Map[String,AttributeValue]] = Outlet.create("DynamoQuerySource.out")

  override def shape: SourceShape[Map[String, AttributeValue]] = SourceShape.of(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var resultList:Seq[Map[String,AttributeValue]] = Seq()
    var lastEvaluatedKey:Option[java.util.Map[String,AttributeValue]] = None

    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = {
        if(resultList.isEmpty){
          //pull more results from dynamo
          val baseRequest = new QueryRequest()
            .withTableName(tableName)
            .withKeyConditions(keyConditions.asJava)

          val indexRequest = indexName match {
            case None=> baseRequest
            case Some(actualIndexName)=>baseRequest.withIndexName(actualIndexName)
          }

          val actualRequest = lastEvaluatedKey match {
            case None=>indexRequest
            case Some(actualLastKey)=>indexRequest.withExclusiveStartKey(actualLastKey)
          }

          val result = dynamoClient.query(actualRequest)
          val items = result.getItems.asScala
          val convertedItems = items.map(_.asScala.toMap) //toMap is needed to convert the Mutable map to an immutable one

          lastEvaluatedKey = Option(result.getLastEvaluatedKey)
          resultList = resultList ++ convertedItems.tail
          push(out, convertedItems.head)
        } else {
          //output next from list
          val nextItem :: remainder = resultList
          resultList = remainder
          push(out, nextItem)
        }
      }
    })
  }
}
