package responses

case class ObjectCreatedResponse[T](status:String,entityClass:String, objectId:T)
