package com.theguardian.multimedia.archivehunter.common.clientManagers

import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, AwsCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider, ProfileCredentialsProvider}

trait ClientManagerBase[T] {
  @deprecated("Switch to using the newer AWS SDK version with newCredentialsProvider")
  def credentialsProvider(profileName:Option[String]=None) = {
    import com.amazonaws.auth.{AWSCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
    import com.amazonaws.auth.profile.ProfileCredentialsProvider

    new AWSCredentialsProviderChain(
      new ProfileCredentialsProvider(profileName.getOrElse("default")),
      new ContainerCredentialsProvider(),
      new InstanceProfileCredentialsProvider()
    )
  }

  /**
    * Supply AWS credentials from the most appropriate source - a locally configured profile, a container or an EC2 instance
    * @param maybeProfile optional local profile name to use. Defaults to "default" if not specified.
    * @return the AwsCredentialsProvider
    */
  def newCredentialsProvider(maybeProfile:Option[String]):AwsCredentialsProvider = {
    AwsCredentialsProviderChain.of(
      ProfileCredentialsProvider.create(maybeProfile.getOrElse("default")),
      ContainerCredentialsProvider.builder().build(),
      InstanceProfileCredentialsProvider.create(),
    )
  }

  def getClient(profileName:Option[String]=None):T
}
