package services.datamigration.streamcomponents

import com.amazonaws.services.dynamodbv2.document.Attribute
import com.amazonaws.services.dynamodbv2.model.{AttributeValue, WriteRequest}

object ConvenientTypes {
  type GenericDynamoEntry = Map[String,AttributeValue]
  type DynamoBatchOperation = Map[String, List[WriteRequest]]
}
