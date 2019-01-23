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
  private var existingClientsMap:Map[String,AmazonS3] = Map()

  override def getClient(profileName:Option[String]=None): AmazonS3 = getS3Client(profileName)

  def getS3Client(profileName:Option[String]=None, region:Option[String]=None):AmazonS3 = {
    region match {
      case Some(rgn)=>
        existingClientsMap.get(rgn) match {
          case Some(client)=>client
          case None=>
            val newClient = AmazonS3ClientBuilder.standard().withRegion(rgn).withCredentials(credentialsProvider(profileName)).build()
            this.synchronized({
              existingClientsMap = existingClientsMap.updated(rgn, newClient)
            })
            newClient
        }
      case None=>
        AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()
    }
  }

  def flushCache = this.synchronized({
    existingClientsMap = Map()
  })

  def getAlpakkaS3Client(profileName:Option[String]=None, region:Option[String]=None)(implicit system:ActorSystem,mat:Materializer) = {
    val regionProvider =
      new AwsRegionProvider {
        def getRegion: String = region.getOrElse(config.getOptional[String]("externalData.awsRegion").getOrElse("eu-west-1"))
      }

    val settings = new S3Settings(MemoryBufferType,None,credentialsProvider(profileName),regionProvider,false,None,ListBucketVersion2)
    new S3Client(settings)  //uses the implicit ActorSystem and Materializer pointers
  }
}
