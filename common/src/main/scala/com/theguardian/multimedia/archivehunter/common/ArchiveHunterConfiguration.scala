package com.theguardian.multimedia.archivehunter.common

trait ArchiveHunterConfiguration {
  def getOptional[T](key:String)(implicit converter: String=>T):Option[T]
  def get[T](key:String)(implicit converter: String=>T):T
}

trait ExtValueConverters {
  implicit def StringString(input:String): String = input
  implicit def StringInt(input:String):Int = augmentString(input).toInt

  implicit def StringBool(input:String):Boolean = input.toLowerCase() match {
    case "true"=>true
    case "yes"=>true
    case "false"=>false
    case "no"=>false
    case _=>false
  }

  implicit def StringDouble(input:String):Double = augmentString(input).toDouble
  implicit def StringLong(input:String):Long = augmentString(input).toLong
}
