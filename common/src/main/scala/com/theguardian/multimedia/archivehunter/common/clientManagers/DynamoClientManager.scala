package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbAsyncClientBuilder, DynamoDbClient}
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient

import javax.inject.{Inject, Singleton}

@Singleton
class DynamoClientManager @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase [DynamoDbClient]{
  override def getClient(profileName: Option[String]): DynamoDbClient = getNewDynamoClient(profileName)

  def getNewDynamoClient(profileName: Option[String]) =
    DynamoDbClient.builder().credentialsProvider(newCredentialsProvider(profileName)).build()

  def getNewAsyncDynamoClient(profileName:Option[String]=None) =
    DynamoDbAsyncClient.builder()
      .httpClientBuilder(  //we need to specify an explicit client as the runtime won't choose for us
        NettyNioAsyncHttpClient.builder().maxConcurrency(100).maxPendingConnectionAcquires(10_000)
      )
      .credentialsProvider(newCredentialsProvider(profileName))
      .build()
}
