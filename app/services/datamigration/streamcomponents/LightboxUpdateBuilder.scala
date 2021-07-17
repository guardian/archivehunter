package services.datamigration.streamcomponents

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{AbstractInHandler, AbstractOutHandler, GraphStage, GraphStageLogic}
import com.amazonaws.services.dynamodbv2.document.Attribute
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, WriteRequest}
import ConvenientTypes._
import akka.stream.scaladsl.{Flow, GraphDSL}
import com.sun.tools.javac.code.TypeTag
import org.slf4j.LoggerFactory
import shapeless.{Poly1, Poly2, Typeable}

import scala.reflect.ClassTag

/**
  * takes in a stream of DynamoDB items and outputs a stream of requests suitable for sending to BatchWriteItemRequest
  * @param primaryKeyName the name of the primary key to transform
  * @param pkTransformFunction a function that takes in the old primary key value and outputs the new primary key value
  * @tparam P data type of the 'old' primary key
  * @tparam Q data type of the 'new' primary key
  */
class LightboxUpdateBuilder[P,Q](primaryKeyName:String, pkTransformFunction:(P)=>Q, classOfSource:Class[P]) extends GraphStage[FlowShape[GenericDynamoEntry,UpdateRequest]] {
  private final val in:Inlet[GenericDynamoEntry] = Inlet.create("LightboxUpdateBuilder.in")
  private final val out:Outlet[UpdateRequest] = Outlet.create("LightboxUpdateBuilder.out")

  private val logger = LoggerFactory.getLogger(getClass)

  override def shape = FlowShape.of(in, out)

//  def attribForType[T](wantedType:TypeTag[T], attrib:AttributeValue):T = {
//    wantedType match {
//      case TypeTag.INT=>attrib.getN.toInt.asInstanceOf[T] //"Int"|"Integer"
//      //case TypeTag.valueOf("String")=>attrib.getS.asInstanceOf[T]
//      case TypeTag.LONG=>attrib.getN.toLong.asInstanceOf[T]
//      case TypeTag.FLOAT=>attrib.getN.toFloat.asInstanceOf[T]
//      case TypeTag.BOOLEAN=>attrib.getBOOL.asInstanceOf[T]
//    }
//  }

//  def attribForType[T](attrib:AttributeValue, wantedType:ClassTag[T]):T = {
//    wantedType.runtimeClass.getGenericSuperclass == typeOf
//    wantedType.runtimeClass match {
//      case stringClass=>attrib.getS.asInstanceOf[T]
//      case
//    }
//  }

  object extractClass extends Poly1 {
    implicit def case0[T](implicit t:Typeable[T]) = at[T](t=>t.getClass)
  }

  object attribForType extends Poly2 {
    implicit def caseString = at[AttributeValue, Class[String]]((attr,_)=>attr.getS)
    implicit def caseInt = at[AttributeValue, Class[Int]]((attr, _)=>attr.getN.toInt)
    implicit def caseFloat = at[AttributeValue, Class[Float]](((attr,_)=>attr.getN.toFloat))
    implicit def caseBool = at[AttributeValue, Class[Boolean]]((attr, _)=>attr.getBOOL)
    implicit def other = at[AttributeValue, Class[_]]((_, classType)=>throw new RuntimeException(s"Can't unmarshal type ${classType.toString} from Dynamo"))
    implicit def catchall = at[_,_]((_, _)=>throw new RuntimeException("unacceptable class"))
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(out, new AbstractOutHandler {
      override def onPull(): Unit = pull(in)
    })

    setHandler(in, new AbstractInHandler {
      override def onPush(): Unit = {
        val elem = grab(in)

        elem.get(primaryKeyName) match {
          case Some(pkAttrib)=>
            val pkValue = attribForType(pkAttrib, classOfSource)
            val updatedPkValue = pkTransformFunction(pkValue.asInstanceOf[P])
            val updatedPkAttribute = new Attribute(primaryKeyName, updatedPkValue)
            val updatedRecord = elem ++ Map(primaryKeyName->updatedPkAttribute)
            val recordToDelete = Map(primaryKeyName->pkAttrib)
            logger.info(s"Updating $primaryKeyName ${pkValue.toString} to ${updatedPkValue.toString}")
            push(out, UpdateRequest(updatedRecord, recordToDelete))
          case None=>

            logger.error(s"Record $elem did not have any primary key field called $primaryKeyName")
            failStage(new RuntimeException("Encountered record of incorrect type, see logs"))
        }
      }
    })
  }
}

object LightboxUpdateBuilder {
  def apply[P,Q](primaryKeyName:String, pkTransformFunction:(P)=>Q, classOfSource:Class[P]) = GraphDSL.create() { builder=>
    val b = builder.add(new LightboxUpdateBuilder(primaryKeyName, pkTransformFunction, classOfSource))
    FlowShape.of(b.in, b.out)
  }


}