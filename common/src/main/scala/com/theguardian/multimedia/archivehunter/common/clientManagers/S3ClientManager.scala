package com.theguardian.multimedia.archivehunter.common.clientManagers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.s3.impl.ListBucketVersion2
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.alpakka.s3.{MemoryBufferType, S3Settings}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
import com.amazonaws.regions.AwsRegionProvider
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.theguardian.multimedia.archivehunter.common.ArchiveHunterConfiguration
import javax.inject.{Inject, Singleton}

@Singleton
class S3ClientManager @Inject() (config:ArchiveHunterConfiguration) extends ClientManagerBase[AmazonS3]{

  override def getClient(profileName:Option[String]=None): AmazonS3 = getS3Client(profileName)

  def getS3Client(profileName:Option[String]=None):AmazonS3 =
    AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()


  def getAlpakkaS3Client(profileName:Option[String]=None)(implicit system:ActorSystem,mat:Materializer) = {
    val regionProvider =
      new AwsRegionProvider {
        def getRegion: String = config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1")
      }

    val settings = new S3Settings(MemoryBufferType,None,credentialsProvider(profileName),regionProvider,false,None,ListBucketVersion2)
    new S3Client(settings)  //uses the implicit ActorSystem and Materializer pointers
  }
}
