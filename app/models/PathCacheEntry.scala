package models

case class PathCacheEntry(level:Int, key:String, parent:Option[String], collection:String)
