package helpers

import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import com.amazonaws.services.sns.model.{SubscribeRequest, UnsubscribeRequest}
import com.amazonaws.services.sqs.model.SetQueueAttributesRequest
import com.theguardian.multimedia.archivehunter.common.clientManagers.{SNSClientManager, SQSClientManager, STSClientManager}
import com.theguardian.multimedia.archivehunter.common.cmn_models.{ProxyFrameworkInstance, ProxyFrameworkInstanceDAO}
import io.circe.Error
import javax.inject.Inject
import models._
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.Printer

/**
  * injectable helper class that contains operations for managing ProxyFramework instances
  * @param config
  * @param sqsClientManager
  */
class ProxyFramework @Inject()(config:Configuration,
                               sqsClientManager: SQSClientManager,
                               stsClientManager:STSClientManager,
                               snsClientManager:SNSClientManager,
                               proxyFrameworkInstanceDAO:ProxyFrameworkInstanceDAO) extends AwsSqsPolicyDecoder {
  private val logger = Logger(getClass)

  private val awsProfile = config.getOptional[String]("externalData.awsProfile")
  private val sqsClient = sqsClientManager.getClient(awsProfile)
  private val mainAppQueueArn = config.get[String]("proxyFramework.notificationsQueueArn")
  private val mainAppQueueUrl = config.get[String]("proxyFramework.notificationsQueue")

  private val roleDuration = config.getOptional[Int]("proxyFramework.roleDuration").getOrElse(900)  //900 is the minimum value
  protected def getStsClient(region:String):AWSSecurityTokenService = stsClientManager.getClientForRegion(awsProfile, region)



  /**
    * try to "attach" the given ProxyFrameworkInstance.  This entails subscribing the main app's transcode message queue
    * onto the ProxyFrameworkInstance's output topic, and adding the topic to the queue's security policy.
    * These operations are performed asynchronously in parallel.  On failure of either, we attempt rollback of BOTH results.
    * @param f ProxyFrameworkInstance to attach to
    * @return a Future, containing a Try indicating success or failure. On success, a tuple of (subscriptionId, policyResult) strings.
    */
  def attachFramework(f:ProxyFrameworkInstance) =
    Future.sequence(Seq(makeSubscription(f), addToPolicy(f))).map(result=>Success((result.head, result(1)))).recoverWith({
      case err:Throwable=>
        logger.error(s"Could not attach to framework $f:", err)
        Future.sequence(Seq(removeSubscription(f), removeFromPolicy(f)))
          .map(results=>Failure(err))
          .recover({
            case rollbackErr:Throwable=>
              logger.error(s"Could not fully roll back framework attach: ", rollbackErr)
              Failure(err)
          })
    })

  /**
    * try to "detach" the give ProxyFrameworkInstance. This entains unsubscribing the main apps transcode message qyeye
    * from the ProxyFrameworkInstance's output topic, and removing the topic from the queue's security policy.
    * These operations are performed asynchronously in parallel.  On failure, no rollback is attempted; it should be safe to
    * re-try the operation.
    * @param f ProxyFrameworkInstance to detach from
    * @return a Future, containing a Try indicating success or failure.  On success, a tuple of (boolean, policyResult) is returned;
    *         the boolean indicates whether there was anything to unsubscribe or not.
    */
  def detachFramework(f:ProxyFrameworkInstance) =
    Future.sequence(Seq(removeSubscription(f), removeFromPolicy(f)))
      .map(result=>Success((result.head.asInstanceOf[Boolean], result(1).asInstanceOf[String])))
      .recover({
        case err:Throwable=>
          logger.error(s"Could not detach framework $f", err)
          Failure(err)
      })

  /**
    * uses the provided admin role to subscribe the main app's message queue to the framework deployment's output topic
    * @param f ProxyFrameworkInstance
    * @return a Future containing the Subscription ID of the subscription that was made
    */
  def makeSubscription(f:ProxyFrameworkInstance):Future[String] = Future {
    logger.info(s"Performing subscription from ${f.outputTopicArn} to $mainAppQueueArn")
    implicit val stsClient = getStsClient(f.region)
    snsClientManager.getTemporaryClient(f.region, f.roleArn) match {
      case Success(snsClient) =>
        val rq = new SubscribeRequest()
          .withTopicArn(f.outputTopicArn)
          .withProtocol("sqs")
          .withEndpoint(mainAppQueueArn)
        val result = snsClient.subscribe(rq)
        result.getSubscriptionArn
      case Failure(err) =>
        logger.error(s"Could not connect to provided role ${f.roleArn}", err)
        throw err
    }
  }

  /**
    * gets the current policy on our queue.
    * @return None if there is no existing policy. Left with an error if the policy Json failed to parse; Right with the policy
    *         represented as a AwsSqsPolicy object if it parses properly.
    */
  def getCurrentPolicy:Future[Option[Either[Error,AwsSqsPolicy]]] = Future {
    val result = sqsClient.getQueueAttributes(mainAppQueueUrl,List("Policy").asJava)
    result.getAttributes.asScala.get("Policy").map(policyStr=>{
      logger.debug(s"Policy string: $policyStr")
      io.circe.parser.parse(policyStr).flatMap(_.as[AwsSqsPolicy])
    })
  }

  /**
    * adds the output topic of the provided ProxyFrameworkInstance to the queue policy of our queue
    * @param f ProxyFrameworkInstance describing the instance we're attaching to
    * @return a Future containing a String indicating success.  The Future fails on error; use recover() or recoverWith() to catch this
    */
  def addToPolicy(f:ProxyFrameworkInstance):Future[String] = {
    logger.info(s"Adding ${f.outputTopicArn} to queue policy for $mainAppQueueArn")
    val newPolicyFuture = getCurrentPolicy.map({
      case None=> //no policy exists yet
        AwsSqsPolicy.createNew(Seq(
          AwsSqsPolicyStatement.forInputOutput(mainAppQueueArn,f.outputTopicArn)
        ))
      case Some(Left(err))=>
        logger.error(s"Could not parse existing queue policy for $mainAppQueueArn: ${err.toString}")
        throw new RuntimeException(s"Could not parse existing queue policy for $mainAppQueueArn: ${err.toString}")
      case Some(Right(policy))=>
        policy.withNewStatement(AwsSqsPolicyStatement.forInputOutput(mainAppQueueArn,f.outputTopicArn))
    })

    newPolicyFuture.map(newPolicy=>{
      val str = newPolicy.asJson.noSpaces
      logger.debug(s"Updating policy of $mainAppQueueUrl to $str")
      val rq = new SetQueueAttributesRequest().withQueueUrl(mainAppQueueUrl).withAttributes(Map("Policy"->str).asJava)
      val result = sqsClient.setQueueAttributes(rq)
      result.toString
    })
  }

  /**
    * removes the output topic of the provided ProxyFrameworkInstance from the queue policy of our queue
    * @param f ProxyFrameworkInstance describing the instance we're detaching from
    * @return a Future containing a String indicating success.  The Future fails on error; use recover() or recoverWith to catch this.
    */
  def removeFromPolicy(f:ProxyFrameworkInstance):Future[String] = {
    logger.info(s"Removing $f from the policy of $mainAppQueueArn")
    val newPolicyFuture = getCurrentPolicy.map({
      case None=> //no policy exists yet, so nothing needs to be done
        None
      case Some(Left(err))=>
        logger.error(s"Could not parse existing queue policy for $mainAppQueueArn: ${err.toString}")
        throw new RuntimeException(s"Could not parse existing queue policy for $mainAppQueueArn: ${err.toString}")
      case Some(Right(policy))=>
        Some(policy.withoutStatement(AwsSqsPolicyStatement.forInputOutput(mainAppQueueArn, f.outputTopicArn)))
    })

    newPolicyFuture.map(newPolicy=>{
      val str = newPolicy.asJson.noSpaces
      logger.debug(s"Updating policy of $mainAppQueueUrl to $str")
      val rq = new SetQueueAttributesRequest().withQueueUrl(mainAppQueueUrl).withAttributes(Map("Policy" -> str).asJava)
      val result = sqsClient.setQueueAttributes(rq)
      result.toString
    })
  }

  /**
    * uses the provided admin role to unsubscribe the main app's message queue from the framework deployment's output topic
    * @param f
    * @return
    */
  def removeSubscription(f:ProxyFrameworkInstance):Future[Boolean] = Future {
    f.subscriptionId match {
      case None=>
        logger.error(s"ProxyFramework $f does not have a subscription ID so I can't disconnect")
        false
      case Some(subId)=>
        logger.info(s"Unsubscribing ID $subId for $f")
        implicit val stsClient = stsClientManager.getClientForRegion(awsProfile, f.region)
        snsClientManager.getTemporaryClient(f.region, f.roleArn) match {
          case Success(snsClient) =>
            val rq = new UnsubscribeRequest().withSubscriptionArn(subId)
            val result = snsClient.unsubscribe(rq)
            true
          case Failure(err) =>
            logger.error(s"Could not assume role ${f.roleArn} to remove subscription", err)
            throw err
        }
    }
  }

  /**
    * perform setup of a new stack, as called from the controller.  This involves saving the record to database and then
    * attaching the topic to the queue
    * @param clientRequest request object from the client detailing the stack we are trying to connect
    * @param stack CloudFormation's description of the actual stack
    * @return None if the stack could not be found. Otherwise, a Future containing a Try
    *         with either a tuple of (subscription ID, policyResult) or an error describing what went wrong.
    */
  def setupDeployment(rec:ProxyFrameworkInstance) = {
        logger.debug(s"Got stack info: $rec")
        proxyFrameworkInstanceDAO
          .put(rec)
          .flatMap(_=> {
            logger.debug(s"Write succeeded, commencing setup...")
            attachFramework(rec).flatMap({
              case Success(results) =>
                logger.debug(s"Subscription succeeded, updating record with subscription ID")
                val updatedRec = rec.copy(subscriptionId = Some(results._1))
                proxyFrameworkInstanceDAO
                  .put(updatedRec)
                  .map(_=>Success(results))
                  .recover({
                    case err:Throwable=>
                      logger.error(s"Can't write proxy framework instance to database: ${err.getMessage}", err)
                      Failure(err)
                  })
              case Failure(err) =>
                logger.error("Failed to attach framework to app instance")
                Future(Failure(err))
            })
          }).recover({
          case err:Throwable=>
            logger.error(s"Can't set up proxy framework instance $rec: ${err.getMessage}", err)
            Failure(err)
        })
  }
}
