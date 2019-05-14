package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.services.simpleemail.{AmazonSimpleEmailService, AmazonSimpleEmailServiceClientBuilder}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import javax.inject.{Inject, Singleton}

@Singleton
class SESClientManager @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase[AmazonSimpleEmailService] {
  override def getClient(profileName: Option[String]): AmazonSimpleEmailService =
    AmazonSimpleEmailServiceClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()

}
