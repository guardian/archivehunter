package com.theguardian.multimedia.archivehunter.common.errors

case class NothingFoundError(objectType:String, msg:String) extends GenericArchiveHunterError {
  override def toString: String = s"$objectType not found: $msg"
}
