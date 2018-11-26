package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.auth.{AWSCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
import com.amazonaws.auth.profile.ProfileCredentialsProvider

trait ClientManagerBase[T] {
  def credentialsProvider(profileName:Option[String]=None) = new AWSCredentialsProviderChain(
    new ProfileCredentialsProvider(profileName.getOrElse("default")),
    new ContainerCredentialsProvider(),
    new InstanceProfileCredentialsProvider()
  )

  def getClient(profileName:Option[String]=None):T
}
