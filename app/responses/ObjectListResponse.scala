package responses

case class ObjectListResponse[T](status:String,entityClass:String, entries:T, entryCount: Int)
