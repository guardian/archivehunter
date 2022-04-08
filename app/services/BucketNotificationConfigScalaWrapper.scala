package services

import com.amazonaws.services.s3.model.{BucketNotificationConfiguration, NotificationConfiguration}
import scala.jdk.CollectionConverters._

/**
  * Scala case-class compatible wrapper for BucketNotificationConfiguration.
  * This allows us to easily track if there has been an update to the configuration and only make the write call
  * if an update has taken place.
  * @param wrapped BucketNotificationConfiguration object to be wrapped
  * @param isUpdated boolean flag. Don't specify this on initial construction and it will default to `false`.
  */
case class BucketNotificationConfigScalaWrapper(wrapped:BucketNotificationConfiguration, isUpdated:Boolean) {
  def withConfiguration(name:String, config:NotificationConfiguration):BucketNotificationConfigScalaWrapper = {
    copy(wrapped.addConfiguration(name, config), true)
  }

  def withConfiguration(defn:(String, NotificationConfiguration)):BucketNotificationConfigScalaWrapper = {
    withConfiguration(defn._1, defn._2)
  }

  def getConfigurations:Map[String, NotificationConfiguration] = {
    wrapped.getConfigurations.asScala.toMap
  }

  def getConfigurationByName(name:String) = {
    wrapped.getConfigurationByName(name)
  }

  def withoutConfiguration(name:String):BucketNotificationConfigScalaWrapper = {
    wrapped.removeConfiguration(name)
    copy(wrapped, true)
  }
}

object BucketNotificationConfigScalaWrapper {
  def apply(wrapped:BucketNotificationConfiguration) = new BucketNotificationConfigScalaWrapper(wrapped, isUpdated=false)
}
