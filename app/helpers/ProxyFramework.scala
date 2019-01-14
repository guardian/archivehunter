package helpers

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicSessionCredentials, STSAssumeRoleSessionCredentialsProvider}
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceAsyncClientBuilder
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import com.amazonaws.services.sns.model.{SubscribeRequest, UnsubscribeRequest}
import com.theguardian.multimedia.archivehunter.common.clientManagers.SQSClientManager
import javax.inject.Inject
import models.ProxyFrameworkInstance
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

class ProxyFramework @Inject()(config:Configuration,sqsClientManager: SQSClientManager){
  private val logger = Logger(getClass)

//  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
//  private val sqsClient = sqsClientManager.getClient(awsProfile)

  def getStsClient(region:String) = AWSSecurityTokenServiceAsyncClientBuilder.standard().withRegion(region).build()

  def getTemporarySnsClient(region:String, roleArn:String) = {
    val stsClient = getStsClient(region)

    val builder = new STSAssumeRoleSessionCredentialsProvider.Builder(roleArn,"archiveHunterSetup")
      .withRoleSessionDurationSeconds(30)
      .withStsClient(stsClient)

    AmazonSNSClientBuilder.standard().withCredentials(builder.build()).build()
  }

  /**
    * uses the provided admin role to subscribe the main app's message queue to the framework deployment's output topic
    * @param f ProxyFrameworkInstance
    * @return a Future containing the Subscription ID of the subscription that was made
    */
  def connectFramework(f:ProxyFrameworkInstance):Future[String] = Future {
    val myQueueName = config.get[String]("proxyFramework.replyQueue")

    logger.info(s"Performing subscription from ${f.outputTopicArn} to ${config.get[String]("proxyFramework.notificationsQueue")}")
    val snsClient = getTemporarySnsClient(f.region, f.roleArn)
    val rq = new SubscribeRequest()
      .withTopicArn(f.outputTopicArn)
      .withProtocol("AmazonSQS")
      .withEndpoint(config.get[String]("proxyFramework.notificationsQueue"))
    val result = snsClient.subscribe(rq)
    result.getSubscriptionArn
  }

  /**
    * uses the provided admin role to unsubscribe the main app's message queue from the framework deployment's output topic
    * @param f
    * @return
    */
  def disconnectFramework(f:ProxyFrameworkInstance):Future[Boolean] = Future {
    f.subscriptionId match {
      case None=>
        logger.error(s"ProxyFramework $f does not have a subscription ID so I can't disconnect")
        false
      case Some(subId)=>
        logger.info(s"Unsubscribing ID $subId for $f")
        val snsClient = getTemporarySnsClient(f.region, f.roleArn)
        val rq = new UnsubscribeRequest().withSubscriptionArn(subId)
        val result = snsClient.unsubscribe(rq)
        true
    }
  }
}
