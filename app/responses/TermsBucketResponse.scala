package responses

case class BucketEntry(key:String, count:Int)

case class TermsBucketResponse(status:String, entries:Seq[BucketEntry])

object BucketEntry extends ((String, Int)=>BucketEntry) {
  def fromRawData(data:Map[String,Any]) = new BucketEntry(data("key").asInstanceOf[String], data("doc_count").asInstanceOf[Int])
}

object TermsBucketResponse extends ((String, Seq[BucketEntry])=>TermsBucketResponse) {
  def fromRawData(status:String, data:Any) = {
    val actualData=data.asInstanceOf[Map[String, Any]]
    val buckets = actualData("buckets").asInstanceOf[List[Map[String,Any]]]

    new TermsBucketResponse(status, buckets.map(item=>BucketEntry.fromRawData(item)))
  }
}