package requests

import io.circe.{Decoder, Encoder}
import models.UserProfileField

/**
  * how to update a list - overwrite the whole thing, add values, or remove them?
  */
object FieldUpdateOperation extends Enumeration {
  val OP_OVERWRITE,OP_ADD,OP_REMOVE = Value
}

/**
  * this object represents a request to update a single facet of the user profile
  * @param user user ID (email) to update
  * @param fieldName field name to update.  This must be an entry in [[UserProfileField]]
  * @param stringValue new string value. Specify this if the field expects a single value
  * @param listValue new list value. Specify this if the field expects a list
  * @param operation how to perform an update - should it overwrite, add, or remove?
  */
case class UserProfileFieldUpdate (user:String, fieldName:UserProfileField.Value, stringValue:Option[String],
                                   listValue:Option[Seq[String]], operation:FieldUpdateOperation.Value)

/**
  * mix in this trait to allow Circe to automatically marshall and unmarshall these requests into Jaon
  */
trait UserProfileFieldUpdateEncoder {
  implicit val fieldUpdateOperationEncoder = Encoder.enumEncoder(FieldUpdateOperation)
  implicit val fieldUpdateOperationDecoder = Decoder.enumDecoder(FieldUpdateOperation)
  implicit val userProfileFieldEncoder = Encoder.enumEncoder(UserProfileField)
  implicit val userProfileFieldDecoder = Decoder.enumDecoder(UserProfileField)
}