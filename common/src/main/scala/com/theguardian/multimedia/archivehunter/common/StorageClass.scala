package com.theguardian.multimedia.archivehunter.common
import com.gu.scanamo.DynamoFormat
import io.circe.{Decoder, Encoder}

object StorageClass extends Enumeration {
  type StorageClass = Value
  val STANDARD,STANDARD_IA,GLACIER,REDUCED_REDUNDANCY = Value

  /**
    * just like the `withName` method but null-safe.
    * @param name
    * @return
    */
  def safeWithName(name:String) = {
    if(name==null){
      STANDARD
    } else {
      super.withName(name)
    }
  }
}

trait StorageClassEncoder {
  implicit val storageClassEncoder = Encoder.enumEncoder(StorageClass)
  implicit val storageClassDecoder = Decoder.enumDecoder(StorageClass)

  implicit val storageClassFormat = DynamoFormat.coercedXmap[StorageClass.Value, String, IllegalArgumentException](
    input=>StorageClass.withName(input)
  )(
    sc=>sc.toString
  )

}