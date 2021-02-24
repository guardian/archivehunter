package com.theguardian.multimedia.archivehunter.common.cmn_models

import com.sksamuel.elastic4s.http.{ElasticError, RequestFailure}

sealed trait IndexerError {
  val itemId: String

  val errorDesc: String

  override def toString: String = s"$itemId: $errorDesc"
}

case class ItemNotFound(override val itemId:String) extends IndexerError {
  override val errorDesc: String = "Item could not be found"
}

case class UnexpectedReturnCode(override val itemId:String, returnCode:Int, reason:Option[String]=None) extends IndexerError {
  override val errorDesc: String = reason match {
    case Some(reason)=>reason
    case None=>s"Got unexpected return code $returnCode from ElasticSearch"
  }
}

case class ESError(override val itemId:String, actualError:ElasticError) extends IndexerError {
  override val errorDesc: String = actualError.toString
}

case class SystemError(override val itemId:String, actualError:Throwable) extends IndexerError {
  override val errorDesc: String = actualError.toString
}

case class ConflictError(override val itemId:String, override val errorDesc:String) extends IndexerError