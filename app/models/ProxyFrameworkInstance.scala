package models

import com.amazonaws.services.cloudformation.model.Stack
import play.api.Logger

import scala.collection.JavaConverters._


object ProxyFrameworkInstance extends ((String,String , String,String, Option[String])=>ProxyFrameworkInstance ){
  private val logger = Logger(getClass)

  /**
    * gets the values of InputTopic and ReplyTopic outputs of the given stack and returns them as a tuple
    * @param s Stack instance giving details of the CF stack
    * @return a Tuple of (InputTopicArn, ReplyTopicArn) or None if one or both was missing
    */
  private def getTopicInfo(s:Stack):Option[(String,String,String)] = {
    val outs = s.getOutputs.asScala

    val topicRefs = Seq("InputTopic","ReplyTopic","ManagementRole")
      .map(k=>outs.find(_.getOutputKey==k))
      .collect({case Some(value)=>value})

    if(topicRefs.length!=3){
      logger.error(s"Stack ${s.getStackName} did not have InputTopic, ReplyTopic and ManagementRole outputs defined")
      None
    } else {
      Some((topicRefs.head.getOutputValue, topicRefs(1).getOutputValue, topicRefs(3).getOutputValue))
    }
  }

  /**
    * builds a ProxyFrameworkInstance object from a Cloudformation Stack model.
    * This assumes that it has NOT been subscribed yet
    * @param region region that this stack is in
    * @param summ Instance of Stack from cloudformation's .describeStacks method
    * @return populated ProxyFrameworkInstance, or None (with an error message emitted)
    *         if the stack doesn't have the right outputs defined.
    */
  def fromStackSummary(region:String, summ:Stack) = {
    getTopicInfo(summ).map(infos=>
      new ProxyFrameworkInstance(region, infos._1, infos._2, infos._3, None)
    )
  }
}

case class ProxyFrameworkInstance (region:String, inputTopicArn:String, outputTopicArn:String, roleArn:String, subscriptionId:Option[String])

