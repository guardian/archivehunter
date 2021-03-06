package models
import com.amazonaws.services.elastictranscoder.model.JobOutput
import com.theguardian.multimedia.archivehunter.common.{StorageClassEncoder, ZonedDateTimeEncoder}
import com.theguardian.multimedia.archivehunter.common.cmn_models.IngestMessage
import io.circe._
import io.circe.parser
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

/*
  * Data models for expected JSON messages from Elastic Transcoder
  */

trait ExpectedMessages {}

object TranscoderState extends Enumeration {
  val PROGRESSING, COMPLETED, WARNING, ERROR = Value
}

trait TranscoderMessageDecoder {
  implicit val transcoderStateEncoder = Encoder.encodeEnumeration(TranscoderState)
  implicit val transcoderStateDecoder = Decoder.decodeEnumeration(TranscoderState)
}

case class ETSOutput(id:String, presetId:String, status:String,duration:Option[Long],fileSize:Option[Long],width:Option[Int],height:Option[Int],key:String)

// Amazon Elastic Transcoding message structure
case class AwsElasticTranscodeMsg ( state: TranscoderState.Value,
                                    jobId: String,
                                    pipelineId: String,
                                    outputKeyPrefix:Option[String],
                                    errorCode: Option[Int],
                                    messageDetails: Option[String],
                                    userMetadata:Option[Map[String,String]],
                                    outputs:Option[Seq[ETSOutput]]) extends ExpectedMessages

// Amazon Simple Queueing Service (SQS) message structure, used when sent via SNS
case class AwsSqsMsg (
                       Type: String,
                       MessageId: String,
                       TopicArn: String,
                       Subject: Option[String],
                       Message: String,
                       Timestamp: String,
                     )
 extends TranscoderMessageDecoder with ZonedDateTimeEncoder with StorageClassEncoder {
  def getETSMessage: Either[io.circe.Error, AwsElasticTranscodeMsg] =
    io.circe.parser.parse(Message).flatMap(_.as[AwsElasticTranscodeMsg])

  def getIngestMessge: Either[io.circe.Error, IngestMessage] =
    io.circe.parser.parse(Message).flatMap(_.as[IngestMessage])
}

object AwsSqsMsg extends TranscoderMessageDecoder {
  def fromJsonString(str:String):Either[io.circe.Error, AwsSqsMsg] = {
    io.circe.parser.parse(str).flatMap(_.as[AwsSqsMsg])
  }
}
