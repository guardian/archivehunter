package models
import com.amazonaws.services.elastictranscoder.model.JobOutput
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
  implicit val transcoderStateEncoder = Encoder.enumEncoder(TranscoderState)
  implicit val transcoderStateDecoder = Decoder.enumDecoder(TranscoderState)
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

// Amazon Simple Queueing Service (SQS) message structure
case class AwsSqsMsg (
                       Type: String,
                       MessageId: String,
                       TopicArn: String,
                       Subject: String,
                       Message: String,
                       Timestamp: String,
                     )
 extends TranscoderMessageDecoder {
  def getETSMessage: Either[io.circe.Error, AwsElasticTranscodeMsg] =
    io.circe.parser.parse(Message).flatMap(_.as[AwsElasticTranscodeMsg])
}

object AwsSqsMsg extends TranscoderMessageDecoder {
  def fromJsonString(str:String):Either[io.circe.Error, AwsSqsMsg] = {
    io.circe.parser.parse(str).flatMap(_.as[AwsSqsMsg])
  }
}
