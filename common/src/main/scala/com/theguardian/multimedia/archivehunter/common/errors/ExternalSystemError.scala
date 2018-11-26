package com.theguardian.multimedia.archivehunter.common.errors

case class ExternalSystemError (systemName:String, msg: String) extends GenericArchiveHunterError {
  override def toString: String = s"Error occurred in $systemName: $msg"
}
