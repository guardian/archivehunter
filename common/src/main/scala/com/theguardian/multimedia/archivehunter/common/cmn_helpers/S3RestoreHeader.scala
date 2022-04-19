package com.theguardian.multimedia.archivehunter.common.cmn_helpers

import org.slf4j.LoggerFactory

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.util.{Failure, Success, Try}

case class S3RestoreHeader(ongoingRequest:Boolean, expiryDate:Option[ZonedDateTime])

object S3RestoreHeader {
  private val logger = LoggerFactory.getLogger(getClass)
  /* from the docs:
  If an archive copy is already restored, the header value indicates when Amazon S3 is scheduled to delete the object copy. For example:
x-amz-restore: ongoing-request="false", expiry-date="Fri, 21 Dec 2012 00:00:00 GMT"
If the object restoration is in progress, the header returns the value ongoing-request="true".
   */

  private def parseTime(str:String) = Try { ZonedDateTime.parse(str, DateTimeFormatter.RFC_1123_DATE_TIME) } match {
    case Success(zdt)=>Some(zdt)
    case Failure(err)=>
      logger.error(s"Could not parse $str into a datetime: ${err.getMessage}")
      None
  }

  private def parseBool(str:String):Option[Boolean] = str.toLowerCase  match {
    case "true"=>
      Some(true)
    case "false"=>
      Some(false)
    case _=>
      None
  }

  protected def tokenize(headerContent:String) = {
    val xtractor = "^([^=]+)=(.*)$".r

    headerContent
      .split("\"\\s*,\\s*")
      .map({
        case xtractor(key,value)=>Some((key,value))
        case other:String=>
          logger.warn(s"Could not parse header element $other")
          None
      })
      .collect({case Some(kv)=>kv})
      .toMap
  }

  def apply(headerContent:String) = Try {
    tokenize(headerContent)
      .foldLeft(new S3RestoreHeader(false,None))((header, kv)=>kv._1 match {
        case "ongoing-request"=>
          parseBool(kv._2.stripPrefix("\"").stripSuffix("\"")) match {
            case Some(isOngoing)=>header.copy(ongoingRequest = isOngoing)
            case None=>throw new RuntimeException("Invalid ongoing-request value")
          }
        case "expiry-date"=>
          header.copy(expiryDate = parseTime(kv._2.stripPrefix("\"").stripSuffix("\"")))
      })
  }
}