package models

import io.circe.CursorOp.DownField
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, HCursor}

case class AwsSqsPolicyCondition(ArnEquals: Map[String,String])

case class AwsSqsPolicyStatement(Sid:String,Effect:String,Principal:String, Action:List[String],Resource:String,Condition:Option[AwsSqsPolicyCondition])
case class AwsSqsPolicy(Version:String,Id:String,Statement:Seq[AwsSqsPolicyStatement]) {
  def withNewStatement(newStatement:AwsSqsPolicyStatement) = this.copy(Statement=Statement++Seq(newStatement))

  def withoutStatement(oldStatement:AwsSqsPolicyStatement) = this.copy(Statement=this.Statement.filter(_.Sid!=oldStatement.Sid))

}

object AwsSqsPolicyStatement extends ((String,String,String,List[String],String,Option[AwsSqsPolicyCondition])=>AwsSqsPolicyStatement) {
  def forInputOutput(myArn:String,otherArn:String) = {
    val myArnParts = myArn.split(":")
    val otherArnParts = otherArn.split(":")
    new AwsSqsPolicyStatement(s"${myArnParts.last}${otherArnParts.last}",
      "Allow","*",List("sqs:SendMessage"),myArn,Some(AwsSqsPolicyCondition(Map("aws:SourceArn"->otherArn))))
  }
}

object AwsSqsPolicy extends ((String,String,Seq[AwsSqsPolicyStatement])=>AwsSqsPolicy) {
  def createNew(statements:Seq[AwsSqsPolicyStatement]) = new AwsSqsPolicy("2012-10-17","app-managed-policy",statements)
}

trait AwsSqsPolicyDecoder {
  import io.circe.generic.auto._
  /**
    * Action can be a list or a single value. Scala doesn't like this so we need a custom decoder here.
    */
  implicit val decodeAwsSqsPolicyStatement:Decoder[AwsSqsPolicyStatement] = new Decoder[AwsSqsPolicyStatement] {
    override def apply(c: HCursor): Result[AwsSqsPolicyStatement] = {
      val action:Either[DecodingFailure, List[String]] = c.downField("Action").as[List[String]].fold(
        err=>
          c.downField("Action").as[String].map(value=>List(value)),
        succ=>
          Right(succ)
      )

      for {
        sid <- c.downField("Sid").as[String]
        effect <- c.downField("Effect").as[String]
        principal <- c.downField("Principal").as[String]
        action <- c.downField("Action").as[List[String]].fold(
          err=>
            c.downField("Action").as[String].map(value=>List(value)),
          succ=>
            Right(succ)
        )
        resource <- c.downField("Resource").as[String]
        condition <- c.downField("Condition").as[Option[AwsSqsPolicyCondition]]
      } yield new AwsSqsPolicyStatement(sid, effect, principal, action, resource, condition)
    }
  }
}