package models

import com.amazonaws.services.cloudformation.model.Stack
import play.api.Logger

import scala.collection.JavaConverters._

case class AppStackStage(app:String,stack:String,stage:String)

object ProxyFrameworkInstance extends ((String,AppStackStage , String,String)=>ProxyFrameworkInstance ){
  private val logger = Logger(getClass)

  /**
    * gets the values of InputTopic and ReplyTopic outputs of the given stack and returns them as a tuple
    * @param s Stack instance giving details of the CF stack
    * @return a Tuple of (InputTopicArn, ReplyTopicArn) or None if one or both was missing
    */
  private def getTopicInfo(s:Stack):Option[(String,String)] = {
    val outs = s.getOutputs.asScala

    val topicRefs = Seq("InputTopic","ReplyTopic")
      .map(k=>outs.find(_.getOutputKey==k))
      .collect({case Some(value)=>value})

    if(topicRefs.length!=2){
      logger.error(s"Stack ${s.getStackName} did not have InputTopic and ReplyTopic outputs defined")
      None
    } else {
      Some((topicRefs.head.getOutputValue, topicRefs(1).getOutputValue))
    }
  }

  /**
    * builds a ProxyFrameworkInstance object from a Cloudformation Stack model
    * @param region region that this stack is in
    * @param summ Instance of Stack from cloudformation's .describeStacks method
    * @return populated ProxyFrameworkInstance, or None (with an error message emitted)
    *         if the stack doesn't have the right outputs defined.
    */
  def fromStackSummary(region:String, summ:Stack) = {
    getTopicInfo(summ).map(infos=>
      new ProxyFrameworkInstance(region, null, infos._1, infos._2)
    )
  }
}

case class ProxyFrameworkInstance (region:String, appStackStage: AppStackStage, inputTopicArn:String, outputTopicArn:String)

