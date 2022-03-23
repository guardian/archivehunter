package com.theguardian.multimedia.archivehunter.common

trait DocId {
  /**
    * Calculates an ID unique to this bucket/path combination, that is not longer than the elasticsearch limit
    * @param bucket bucket that the file is coming from
    * @param key path to the file in `bucket`
    */
  def makeDocId(bucket: String, key:String):String = {
    val maxIdLength=512 //the base64 representation always comes out larger than the incoming string. ElasticSearch limits to 512 chars so we must chop a bit shorter
    val encoder = java.util.Base64.getEncoder

    val initialString = bucket + ":" + key
    val initialEncoded = encoder.encodeToString(initialString.toCharArray.map(_.toByte))
    if(initialEncoded.length<=maxIdLength){
      initialEncoded
    } else {
      /* I figure that the best way to get something that should be unique for a long path is to chop out the middle */
      val chunkLength = initialEncoded.length/3
      val stringParts = initialEncoded.grouped(chunkLength).toList
      val midSectionLength = maxIdLength - chunkLength*2  //FIXME: what if chunkLength*2>512??
      stringParts.head + stringParts(1).substring(0, midSectionLength) + stringParts(2)
    }
  }

}
