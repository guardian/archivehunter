package com.theguardian.multimedia.archivehunter.common.clientManagers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.dynamodb.impl.DynamoSettings
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDBAsync, AmazonDynamoDBAsyncClientBuilder}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import javax.inject.{Inject, Singleton}

@Singleton
class DynamoClientManager @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase [AmazonDynamoDBAsync]{

  override def getClient(profileName: Option[String]): AmazonDynamoDBAsync = getNewDynamoClient(profileName)
  def getNewDynamoClient(profileName:Option[String]=None) =
    AmazonDynamoDBAsyncClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()


  def getNewAlpakkaDynamoClient(profileName:Option[String]=None)(implicit system:ActorSystem, mat:Materializer) =
    DynamoClient(
      DynamoSettings(
        config.get[String]("externalData.awsRegion"),
        config.get[String]("externalData.ddbHost"),
        443,
        8,
        credentialsProvider(profileName)
      )
    )

}
