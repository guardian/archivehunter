package responses

case class ObjectListResponseWithSize[T](status:String,entityClass:String, entries:T, entryCount: Int, totalSizes:Seq[Double])
