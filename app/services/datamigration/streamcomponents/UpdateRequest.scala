package services.datamigration.streamcomponents
import ConvenientTypes._
import com.amazonaws.services.dynamodbv2.model.{DeleteRequest, PutRequest, WriteRequest}

import scala.collection.JavaConverters._

/**
  * represents a dynamodb update.
  * Contains two members - a new item to "put" with an updated primary key and the corresponding _old_ primary key
  * which needs to be deleted.
  * @param itemToWrite Map of (attribute_name->attribute) pairs representing the item to write
  * @param itemToDelete Map of(attribute_name->attribute) pairs representing the _primary key only_ of the item to delete
  */
case class UpdateRequest(itemToWrite:GenericDynamoEntry, itemToDelete:GenericDynamoEntry) {
  def marshal = Seq(
    new WriteRequest().withPutRequest(new PutRequest().withItem(itemToWrite.asJava)),
    new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(itemToDelete.asJava))
  )
}
