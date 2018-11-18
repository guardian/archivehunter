package com.theguardian.multimedia.archivehunter.common
import io.circe.{Decoder, Encoder}

object StorageClass extends Enumeration {
  type StorageClass = Value
  val STANDARD,STANDARD_IA,GLACIER,REDUCED_REDUNDANCY = Value
}

trait StorageClassEncoder {
  implicit val storageClassEncoder = Encoder.enumEncoder(StorageClass)
  implicit val storageClassDecoder = Decoder.enumDecoder(StorageClass)
}