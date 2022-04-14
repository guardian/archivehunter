package services

import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.slf4j.LoggerFactory
import play.api.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{Event, GetBucketNotificationConfigurationRequest, GetBucketNotificationConfigurationResponse, LambdaFunctionConfiguration, NotificationConfiguration, NotificationConfigurationFilter, PutBucketNotificationConfigurationRequest}

import java.util
import javax.inject.{Inject, Singleton}
import scala.util.{Success, Try}
import scala.jdk.CollectionConverters._

@Singleton
class BucketNotificationConfigurations @Inject()(s3ClientMgr:S3ClientManager, config:Configuration) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val maybeProfile = config.getOptional[String]("externalData.awsProfile")

  /**
    * Filter function to extract notifications relevant to us - specifically ones that point to a Lambda function called
    * `archivehunter-input-`
    * @param defn notificqtion definition, in the form of a tuple name:String->NotificationConfiguration
    * @return true if this is one of ours, otherwise false
    */
  protected def findRelevantNotification(defn:LambdaFunctionConfiguration):Boolean = defn.lambdaFunctionArn().contains("archivehunter-input")

  private val expectedEvents = Set(
    Event.S3_OBJECT_CREATED,
    Event.S3_OBJECT_REMOVED,
    Event.S3_OBJECT_RESTORE_COMPLETED,
    Event.S3_OBJECT_RESTORE_DELETE,
    Event.S3_REDUCED_REDUNDANCY_LOST_OBJECT,
    Event.S3_LIFECYCLE_TRANSITION,
    Event.S3_LIFECYCLE_EXPIRATION
  )

  /**
    * Creates a new configuration for the bucket monitor lambda function
    * @param lambdaArn ARN of the lambda function
    * @return a new LambdaConfiguration
    */
  def createNewNotification(lambdaArn:String) = {
    LambdaFunctionConfiguration.builder
      .lambdaFunctionArn(lambdaArn)
      .events(util.EnumSet.copyOf(expectedEvents.asJavaCollection))
      .build()
  }

  /**
    * If the given notification configuration needs updating, then returns an updated version of it.
    * Otherwise, returns None
    * @param configuration s3 notification configuration
    * @return
    */
  protected def maybeUpdate(configuration: LambdaFunctionConfiguration):Option[LambdaFunctionConfiguration] = {
    val events = configuration.events().asScala.toSeq.toSet
    logger.debug(s"NotificationConfiguration has these events: ${events.mkString(";")}")
    val missingEvents = expectedEvents.diff(events)

    val configBuilder = configuration.toBuilder
    val emptyFilter = NotificationConfigurationFilter.builder().build()

    val firstUpdate = if(missingEvents.nonEmpty) {
      logger.info(s"NotificationConfiguration is missing the following events: ${missingEvents.mkString(";")}, it will be re-written")
      Some(configBuilder.events(expectedEvents.asJava))
    } else {
      None
    }

    val secondUpdate = if(Option(configuration.filter()).isDefined) {
      logger.info(s"NotificationConfiguration has a filter present: ${configuration.filter()}, removing")
      Some(configBuilder.filter(emptyFilter))
    } else {
      None
    }

    (firstUpdate, secondUpdate) match {
      case (None, None) => None
      case (Some(u), None)=> Some(u.build())
      case (None, Some(u))=> Some(u.build())
      case (Some(evts), Some(_)) => Some(evts.filter(emptyFilter).build())
    }
  }

  /**
    * If we need to update the given LambdaFunctionConfiguration, returns Some() with the updated config.
    * Otherwise returns None
    * @param defn existing LambdaFunctionConfiguration
    * @return either an updated configuration or None
    */
  protected def maybeUpdateNotification(defn:LambdaFunctionConfiguration):Option[LambdaFunctionConfiguration] = {
    logger.debug(s"Found Lambda notification with name ${defn.id()}")

    if(defn.lambdaFunctionArn().contains("archivehunter-input")) {
      maybeUpdate(defn)
    } else {
      None
    }
  }

  protected def isAdditionRequired(f:java.util.List[LambdaFunctionConfiguration]):Boolean = {
    ! f.asScala.exists(_.lambdaFunctionArn().contains("archivehunter-input"))
  }
  /**
    * Returns true if there are none of our notifications found in the given configuration, therefore requiring
    * a new configuration to be added
    * @param config BucketNotificaitonConfiguration instance to test
    * @return a boolean value indicating whether we need to add one of our own configurations
    */
  protected def isAdditionRequired(config:NotificationConfiguration.Builder):Boolean = {
    isAdditionRequired(config
      .build()
      .lambdaFunctionConfigurations()
    )
  }

  /**
    * Gathers the additions and modifications, compares them to the previous values and writes to S3 if they differ
    * @param bucketName bucket name to write to
    * @param configResponse initial BucketNotificationConfigurationResponse
    * @param maybeLambdaArn lambda ARN for creating a new notification if required
    * @param requiredUpdates list of updates required to existing notifications
    * @param s3Client implicitly provided AmazonS3 client object
    * @return a Try, containing a tuple of two boolean values. The first is `true` if updates were required, the second is true if they were written.
    *         This is for compatibility with the return value of parent function verifyNotificationSetup
    */
  private def writeUpdatesIfRequired(bucketName:String,
                                     configResponse:GetBucketNotificationConfigurationResponse,
                                     maybeLambdaArn:Option[String],
                                     requiredUpdates:Seq[LambdaFunctionConfiguration])(implicit s3Client:S3Client): Try[(Boolean, Boolean)] = {
    val initialConfiguration = NotificationConfiguration.builder()
      .lambdaFunctionConfigurations(configResponse.lambdaFunctionConfigurations())
      .eventBridgeConfiguration(configResponse.eventBridgeConfiguration())
      .queueConfigurations(configResponse.queueConfigurations())
      .topicConfigurations(configResponse.topicConfigurations())

    //step one - do we need to add a new monitoring configuration? If so put it into the list
    val addedConfiguration = if (isAdditionRequired(initialConfiguration)) {
      logger.debug(s"$bucketName: No archivehunter lambda found, adding one...")
      maybeLambdaArn match {
        case Some(lambdaArn) =>
          val existingConfigs = configResponse.lambdaFunctionConfigurations().asScala
          val updatedConfigs = existingConfigs :+ createNewNotification(lambdaArn)
          initialConfiguration.lambdaFunctionConfigurations(updatedConfigs.asJava)
        case None =>
          throw new RuntimeException("Cannot add a lambda monitor because externalData.bucketMonitorLambdaARN is not set in the application config file")
      }
    } else {
      initialConfiguration
    }

    //step two - do we need to update any of the existing configurations? If so put them into the list
    val updatedConfiguration = if(requiredUpdates.nonEmpty) {
      addedConfiguration.lambdaFunctionConfigurations(requiredUpdates.asJava)
    } else {
      addedConfiguration
    }

    //step three - if the config we built has no changes then do nothing, otherwise write out the updated configuration to S3
    if(updatedConfiguration==initialConfiguration) {
      logger.info(s"$bucketName: No updates were required")
      Success((false, false))
    } else {
      Try {
        s3Client.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
          .bucket(bucketName)
          .notificationConfiguration(updatedConfiguration.build())
          .build())
      }.map(_ => (true, true))
    }
  }

  /**
    *
    * @param bucketName
    * @param region
    * @param shouldWriteUpdates
    * @return a Try, which fails on error and on success contains a tuple of two boolean values.
    *         The first value is `true` if updates to the given bucket were _required_, and the second value
    *         is `true` if required updates to the bucket were _made_.
    */
  def verifyNotificationSetup(bucketName:String, region:Option[Region], shouldWriteUpdates:Boolean) = {
    val maybeLambdaArn = config.getOptional[String]("externalData.bucketMonitorLambdaARN")

    implicit val s3client = s3ClientMgr.getS3Client(maybeProfile, region)
    logger.debug(s"Looking for S3 notifications on bucket $bucketName in region ${region.getOrElse("default")}")

    for {
      response <- Try { s3client.getBucketNotificationConfiguration(GetBucketNotificationConfigurationRequest.builder().bucket(bucketName).build()) }
      requiredUpdates <- Try {
        response
          .lambdaFunctionConfigurations()
          .asScala
          .map(maybeUpdateNotification)
          .collect({case Some(update)=>update})
          .toSeq
      }
      result <- if(shouldWriteUpdates) {
        logger.info(s"$bucketName requires updates, writing the new version...")
        writeUpdatesIfRequired(bucketName, response, maybeLambdaArn, requiredUpdates)
      } else {
        val needsUpdate = requiredUpdates.nonEmpty || isAdditionRequired(response.lambdaFunctionConfigurations())
        if(needsUpdate) {
          logger.info(s"$bucketName configuration is incorrect and requires updates")
        } else {
          logger.info(s"$bucketName - no updates need to be made to notification configuration")
        }
        Success((needsUpdate, false))
      }
    } yield result

  }
}
