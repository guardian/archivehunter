package models

import io.circe.Json
import io.circe.generic.auto._

object JobReport {
  def getFailure(rawContent:Json) = {
    rawContent.as[JobReportError].fold(err=>None, result=>Some(result))
  }

  def getSuccess(rawContent:Json) = {
    rawContent.as[JobReportSuccess].fold(err=>None, result=>Some(result))
  }

  def getResult(rawContent:Json): Option[JobReport] = {
    getFailure(rawContent) match {
      case Some(result)=>return Some(result)
      case None=>
    }
    getSuccess(rawContent) match {
      case Some(result)=>return Some(result)
      case None=>
    }
    None
  }
}

trait JobReport {
  val status:String
}
