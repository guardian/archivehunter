package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.services.ecs.{AmazonECS, AmazonECSClientBuilder}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import javax.inject.{Inject, Singleton}

@Singleton
class ECSClientManager  @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase[AmazonECS]{
  override def getClient(profileName: Option[String]): AmazonECS =
    AmazonECSClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()
}
