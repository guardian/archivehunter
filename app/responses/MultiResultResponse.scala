package responses

case class MultiResultResponse[T,V](status:String, entityClass:String, success:Seq[T], failure:Seq[V])
