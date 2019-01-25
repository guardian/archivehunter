package com.theguardian.multimedia.archivehunter.common.ProxyTranscodeFramework

//This should be kept in-sync with the corresponding file in the ProxyFramework source
import com.theguardian.multimedia.archivehunter.common.ProxyType
import io.circe.{Decoder, Encoder}

case class CreatePipeline (fromBucket:String, toBucket:String)

//This should be kept in-sync with the corresponding file in the main ArchiveHunter source
object RequestType extends Enumeration {
  type RequestType = Value
  val THUMBNAIL, PROXY, ANALYSE, SETUP_PIPELINE, CHECK_SETUP = Value
}

case class RequestModel (requestType: RequestType.Value, inputMediaUri: String,
                         targetLocation:String, jobId:String, force:Option[Boolean],
                         createPipelineRequest: Option[CreatePipeline], proxyType:Option[ProxyType.Value])

trait RequestModelEncoder {
  implicit val requestTypeEncoder = Encoder.enumEncoder(RequestType)
  implicit val requestTypeDecoder = Decoder.enumDecoder(RequestType)

  implicit val proxyTypeEncoder = Encoder.enumEncoder(ProxyType)
  implicit val proxyTypeDecoder = Decoder.enumDecoder(ProxyType)
}