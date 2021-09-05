package services.datamigration.streamcomponents

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, WriteRequest}
import ConvenientTypes._
import akka.stream.scaladsl.GraphDSL
import org.slf4j.LoggerFactory

/**
  * takes in a stream of DynamoDB items and applies `pkTransformFunction` the the primary key field identified by
  * `primaryKeyName`.  If there was an update, emits an `UpdateRequest` containing the old item which needs deleting
  * and the new item which needs writing
  * @param primaryKeyName the name of the primary key to transform
  * @param pkTransformFunction a function that takes in the old primary key value and outputs the new primary key value
  * @param secondaryKeyName an optional Range key name that is associated with the primary index
  */
class LightboxUpdateBuilder(primaryKeyName:String, pkTransformFunction:AttributeValue=>Option[AttributeValue], secondaryKeyName:Option[String]) extends GraphStage[FlowShape[GenericDynamoEntry,UpdateRequest]] {
  private final val in:Inlet[GenericDynamoEntry] = Inlet.create("LightboxUpdateBuilder.in")
  private final val out:Outlet[UpdateRequest] = Outlet.create("LightboxUpdateBuilder.out")

  private val logger = LoggerFactory.getLogger(getClass)

  override def shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = pull(in)
    })

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        elem.get(primaryKeyName) match {
          case Some(pkAttrib)=>
            pkTransformFunction(pkAttrib) match {
              case Some(updatedPkAttribute) =>
                if(updatedPkAttribute==pkAttrib) {
                  logger.info(s"There is no change to be made for $primaryKeyName ${pkAttrib.toString}")
                  pull(in)
                } else {
                  val updatedRecord = elem ++ Map(primaryKeyName -> updatedPkAttribute)
                  val recordToDelete = Seq(
                    Some(primaryKeyName -> pkAttrib),
                    secondaryKeyName.map(k=>k -> elem(k))
                  ).collect({case Some(tupl)=>tupl})
                    .toMap
                  logger.info(s"Updating $primaryKeyName ${pkAttrib.toString} to ${updatedPkAttribute.toString}")
                  push(out, UpdateRequest(updatedRecord, recordToDelete))
                }
              case None =>
                logger.info(s"No update required for $primaryKeyName ${pkAttrib.toString}")
                pull(in)
            }
          case None=>
            logger.error(s"Record $elem did not have any primary key field called $primaryKeyName")
            failStage(new RuntimeException("Encountered record of incorrect type, see logs"))
        }
      }
    })
  }
}

object LightboxUpdateBuilder {
  def apply(primaryKeyName:String, pkTransformFunction:AttributeValue=>Option[AttributeValue], secondaryKeyName:Option[String]) = GraphDSL.create() { builder=>
    val b = builder.add(new LightboxUpdateBuilder(primaryKeyName, pkTransformFunction, secondaryKeyName))
    FlowShape.of(b.in, b.out)
  }
}