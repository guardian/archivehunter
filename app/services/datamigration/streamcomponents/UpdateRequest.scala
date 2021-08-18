package services.datamigration.streamcomponents
import ConvenientTypes._
import com.amazonaws.services.dynamodbv2.model.{DeleteRequest, PutRequest, WriteRequest}

import scala.collection.JavaConverters._

/**
  * represents a dynamodb update.
  * Contains two members - a new item to "put" with an updated primary key and the corresponding _old_ primary key
  * which needs to be deleted.
  * @param itemToWrite Map of (attribute_name->attribute) pairs representing the item to write
  * @param itemToDelete Map of(attribute_name->attribute) pairs representing the _primary key only_  (hash and range, if specified) of the item to delete
  */
case class UpdateRequest(itemToWrite:GenericDynamoEntry, itemToDelete:Option[GenericDynamoEntry]) {
  /**
    * converts this request into a sequence of one or two dynamodb WriteRequests, one representing a write to make and the other a delete to make.
    * if itemToDelete is None, then there is only a write request output
    * @return
    */
  def marshal:Seq[WriteRequest] = Seq(
    Some(new WriteRequest().withPutRequest(new PutRequest().withItem(itemToWrite.asJava))),
    itemToDelete.map(i=>new WriteRequest().withDeleteRequest(new DeleteRequest().withKey(i.asJava)))
  ).collect({case Some(req)=>req})
}

object UpdateRequest {
  def apply(itemToWrite:GenericDynamoEntry, itemToDelete:GenericDynamoEntry) = new UpdateRequest(itemToWrite, Some(itemToDelete))

  def apply(itemToWrite:GenericDynamoEntry) = new UpdateRequest(itemToWrite, None)
}