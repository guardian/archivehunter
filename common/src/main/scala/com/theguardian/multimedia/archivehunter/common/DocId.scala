package com.theguardian.multimedia.archivehunter.common

trait DocId {
  /**
    * Calculates an ID unique to this bucket/path combination, that is not longer than the elasticsearch limit
    * @param bucket bucket that the file is coming from
    * @param key path to the file in `bucket`
    */
  def makeDocId(bucket: String, key:String):String = {
    val maxIdLength=512
    val encoder = java.util.Base64.getEncoder

    val initialString = bucket + ":" + key
    if(initialString.length<=maxIdLength){
      encoder.encodeToString(initialString.toCharArray.map(_.toByte))
    } else {
      /* I figure that the best way to get something that should be unique for a long path is to chop out the middle */
      val chunkLength = initialString.length/3
      val stringParts = initialString.grouped(chunkLength).toList
      val midSectionLength = maxIdLength - chunkLength*2  //FIXME: what if chunkLength*2>512??
      val finalString = stringParts.head + stringParts(1).substring(0, midSectionLength) + stringParts(2)
      encoder.encodeToString(finalString.toCharArray.map(_.toByte))
    }
  }

}
