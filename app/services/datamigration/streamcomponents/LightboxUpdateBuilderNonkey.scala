package services.datamigration.streamcomponents

import akka.stream.scaladsl.GraphDSL
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import org.slf4j.LoggerFactory
import services.datamigration.streamcomponents.ConvenientTypes.GenericDynamoEntry

class LightboxUpdateBuilderNonkey(fieldName:String, pkTransformFunction:AttributeValue=>Option[AttributeValue]) extends GraphStage[FlowShape[GenericDynamoEntry,UpdateRequest]] {
  private final val in:Inlet[GenericDynamoEntry] = Inlet.create("LightboxUpdateBuilderNonkey.in")
  private final val out:Outlet[UpdateRequest] = Outlet.create("LightboxUpdateBuilderNonkey.out")
  private val logger = LoggerFactory.getLogger(getClass)

  override def shape: FlowShape[GenericDynamoEntry, UpdateRequest] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = pull(in)
    })

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        elem.get(fieldName) match {
          case Some(attr)=>
            pkTransformFunction(attr) match {
              case Some(updatedAtt)=>
                val updatedElem = elem ++ Map(fieldName->updatedAtt)
                push(out, UpdateRequest(updatedElem))
              case None=>
                logger.info(s"No update required for $fieldName ${attr.toString}")
                pull(in)
            }
          case None=>
            logger.error(s"Record $elem did not have any primary key field called $fieldName")
            failStage(new RuntimeException("Encountered record of incorrect type, see logs"))
        }
      }
    })
  }
}

object LightboxUpdateBuilderNonkey {
  def apply(fieldName:String, pkTransformFunction:AttributeValue=>Option[AttributeValue])  = GraphDSL.create() { builder=>
    val b = builder.add(new LightboxUpdateBuilderNonkey(fieldName, pkTransformFunction))
    FlowShape.of(b.in, b.out)
  }
}