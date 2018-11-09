package responses

case class ObjectGetResponse[T] (status:String, objectClass: String, entry: T)