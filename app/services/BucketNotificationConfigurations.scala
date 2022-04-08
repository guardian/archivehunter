package services

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{BucketNotificationConfiguration, LambdaConfiguration, NotificationConfiguration, S3Event}
import com.theguardian.multimedia.archivehunter.common.clientManagers.S3ClientManager
import org.slf4j.LoggerFactory
import play.api.Configuration

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
  protected def findRelevantNotification(defn:(String, NotificationConfiguration)):Boolean = {
    logger.debug(s"Notification with name ${defn._1}: ${defn._2.getClass.getName}")
    defn._2 match {
      case lambdaNotification:LambdaConfiguration=>
        lambdaNotification.getFunctionARN.contains("archivehunter-input")
      case _=>
        logger.debug(s"Notification ${defn._1} is not a lambda function notification")
        false
    }
  }

  private val expectedEvents = Set(
    S3Event.ObjectCreated,
    S3Event.ObjectRemoved,
    S3Event.ObjectRestoreCompleted,
    S3Event.ObjectRestoreDelete,
    S3Event.ReducedRedundancyLostObject,
    S3Event.LifecycleTransition,
    S3Event.LifecycleExpiration
  )

  /**
    * Creates a new configuration for the bucket monitor lambda function
    * @param lambdaArn ARN of the lambda function
    * @return a new LambdaConfiguration
    */
  def createNewNotification(lambdaArn:String) = {
    new LambdaConfiguration(lambdaArn, util.EnumSet.copyOf(expectedEvents.asJavaCollection))
  }

  /**
    * If the given notification configuration needs updating, then returns an updated version of it.
    * Otherwise, returns None
    * @param configuration s3 notification configuration
    * @return
    */
  protected def maybeUpdate(configuration: NotificationConfiguration):Option[NotificationConfiguration] = {
    val events = configuration.getEvents.asScala
    logger.debug(s"NotificationConfiguration has these events: ${events.mkString(";")}")
    val missingEvents = expectedEvents.map(_.toString).diff(events)

    val firstUpdate = if(missingEvents.nonEmpty) {
      logger.info(s"NotificationConfiguration is missing the following events: ${missingEvents.mkString(";")}, it will be re-written")
      Some(configuration.withEvents(expectedEvents.map(_.toString).asJava))
    } else {
      None
    }

    val secondUpdate = if(Option(configuration.getFilter).isDefined) {
      logger.info(s"NotificationConfiguration has a filter present: ${configuration.getFilter}, removing")
      Some(configuration.withFilter(null))
    } else {
      None
    }

    (firstUpdate, secondUpdate) match {
      case (None, None) => None
      case (Some(u), None)=> Some(u)
      case (None, Some(u))=> Some(u)
      case (Some(evts), Some(_)) => Some(evts.withFilter(null))
    }
  }

  protected def maybeUpdateNotification(defn:(String, NotificationConfiguration)):Option[(String, NotificationConfiguration)] = {
    logger.debug(s"Notification with name ${defn._1}: ${defn._2.getClass.getName}")

    defn._2 match {
      case lambdaNotification:LambdaConfiguration=>
        if(lambdaNotification.getFunctionARN.contains("archivehunter-input")) {
          maybeUpdate(defn._2).map(updated=>(defn._1, updated))
        } else {
          None
        }
      case _=>
        logger.debug(s"Notification ${defn._1} is not a lambda function notification")
      None
    }
  }

  /**
    * Returns true if there are none of our notifications found in the given configuration, therefore requiring
    * a new configuration to be added
    * @param config BucketNotificaitonConfiguration instance to test
    * @return a boolean value indicating whether we need to add one of our own configurations
    */
  protected def isAdditionRequired(config:BucketNotificationConfigScalaWrapper):Boolean = {
    config.getConfigurations.foldLeft(true)((prevValue, defn)=>{
      defn._2 match {
        case lambdaNotification: LambdaConfiguration=>
          if(lambdaNotification.getFunctionARN.contains("archivehunter-input")) {
            false
          } else {
            prevValue
          }
        case _=>
          prevValue
      }
    })
  }

  /**
    * Gathers the additions and modifications, compares them to the previous values and writes to S3 if they differ
    * @param bucketName bucket name to write to
    * @param initialConfiguration initial BucketNotificationConfiguration within a Scala wrapper
    * @param maybeLambdaArn lambda ARN for creating a new notification if required
    * @param requiredUpdates list of updates required to existing notifications
    * @param s3client implicitly provided AmazonS3 client object
    * @return a Try, containing a tuple of two boolean values. The first is `true` if updates were required, the second is true if they were written.
    *         This is for compatibility with the return value of parent function verifyNotificationSetup
    */
  private def writeUpdatesIfRequired(bucketName:String,
                                     initialConfiguration:BucketNotificationConfigScalaWrapper,
                                     maybeLambdaArn:Option[String],
                                     requiredUpdates:Seq[(String, NotificationConfiguration)])(implicit s3client:AmazonS3) = {
    //step one - do we need to add a new monitoring configuration? If so put it into the list
    val addedConfiguration = if (isAdditionRequired(initialConfiguration)) {
      logger.debug(s"$bucketName: No archivehunter lambda found, adding one...")
      maybeLambdaArn match {
        case Some(lambdaArn) =>
          initialConfiguration.withConfiguration("ArchiveHunter", createNewNotification(lambdaArn))
        case None =>
          throw new RuntimeException("Cannot add a lambda monitor because externalData.bucketMonitorLambdaARN is not set in the application config file")
      }
    } else {
      initialConfiguration
    }

    //step two - do we need to update any of the existing configurations? If so put them into the list
    val updatedConfiguration = if (requiredUpdates.nonEmpty) {
      requiredUpdates.foldLeft(addedConfiguration)((config, defn) => {
        config
          .withoutConfiguration(defn._1)
          .withConfiguration(defn)
      })
    } else {
      addedConfiguration
    }

    //step three - if the config we built has no changes then do nothing, otherwise write out the updated configuration to S3
    if (!updatedConfiguration.isUpdated) {
      logger.info(s"$bucketName - No monitoring configuration updates required")
      Success((false, false))
    } else {
      Try {
        s3client.setBucketNotificationConfiguration(bucketName, updatedConfiguration.wrapped)
      }.map(_=>(true, true))
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
  def verifyNotificationSetup(bucketName:String, region:Option[String], shouldWriteUpdates:Boolean) = {
    val maybeLambdaArn = config.getOptional[String]("externalData.bucketMonitorLambdaARN")

    implicit val s3client = s3ClientMgr.getS3Client(maybeProfile, region)
    logger.debug(s"Looking for S3 notifications on bucket $bucketName in region ${region.getOrElse("default")}")

    for {
      response <- Try { s3client.getBucketNotificationConfiguration(bucketName) }
      requiredUpdates <- Try {
        response
          .getConfigurations
          .asScala
          .map(maybeUpdateNotification)
          .collect({case Some(update)=>update})
          .toSeq
      }
      result <- if(shouldWriteUpdates) {
        logger.info(s"$bucketName requires updates, writing the new version...")
        writeUpdatesIfRequired(bucketName, BucketNotificationConfigScalaWrapper(response), maybeLambdaArn, requiredUpdates)
      } else {
        val needsUpdate = requiredUpdates.nonEmpty || isAdditionRequired(BucketNotificationConfigScalaWrapper(response))
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
