package responses

case class CountResponse[T : Numeric] (status:String, detail:String, count:T)