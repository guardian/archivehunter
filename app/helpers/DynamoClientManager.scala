package helpers

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.alpakka.dynamodb.impl.DynamoSettings
import akka.stream.alpakka.dynamodb.scaladsl.DynamoClient
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class DynamoClientManager @Inject() (config:Configuration){

  def getNewDynamoClient(profileName:Option[String]=None) = {
    val provider = new AWSCredentialsProviderChain(
      new ProfileCredentialsProvider(profileName.getOrElse("default")),
      new ContainerCredentialsProvider(),
      new InstanceProfileCredentialsProvider()
    )

    AmazonDynamoDBAsyncClientBuilder.standard().withCredentials(provider).build()
  }

  def getNewAlpakkaDynamoClient(profileName:Option[String]=None)(implicit system:ActorSystem, mat:Materializer) = {
    val provider = new AWSCredentialsProviderChain(
      new ProfileCredentialsProvider(profileName.getOrElse("default")),
      new ContainerCredentialsProvider(),
      new InstanceProfileCredentialsProvider()
    )

    DynamoClient(
      DynamoSettings(
        config.get[String]("externalData.awsRegion"),
        config.get[String]("externalData.ddbHost"),
        443,
        8,
        provider
      )
    )
  }
}
