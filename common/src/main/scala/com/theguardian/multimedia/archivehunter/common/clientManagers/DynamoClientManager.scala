package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbAsyncClientBuilder, DynamoDbClient}

import javax.inject.{Inject, Singleton}

@Singleton
class DynamoClientManager @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase [DynamoDbClient]{
  override def getClient(profileName: Option[String]): DynamoDbClient = getNewDynamoClient(profileName)

  def getNewDynamoClient(profileName: Option[String]) =
    DynamoDbClient.builder().credentialsProvider(newCredentialsProvider(profileName)).build()

  def getNewAsyncDynamoClient(profileName:Option[String]=None) =
    DynamoDbAsyncClient.builder().credentialsProvider(newCredentialsProvider(profileName)).build()


}
