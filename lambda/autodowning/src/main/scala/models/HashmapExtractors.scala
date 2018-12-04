package models
import scala.collection.JavaConverters._

trait HashmapExtractors {
  def getStringValue(input: Object) = input.asInstanceOf[String]

  def getOptionalString(input: Object) = Option(input).map(_.toString)

  def getIntValue(input: Object) = input.asInstanceOf[String].toInt

  def getValueSeq[Seq[String]](input: Object) = input.asInstanceOf[java.util.List[String]].asScala.toSeq
}
