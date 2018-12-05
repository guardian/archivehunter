package helpers

import akka.actor.ActorSystem
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import com.gu.pandomainauth.service.S3Bucket
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

@Singleton
class InjectableRefresher @Inject() (config:Configuration, actorSystem: ActorSystem) {
  private val profileName = config.getOptional[String]("externalData.awsProfile")
  private val logger = Logger(getClass)

  private val cpChain = new AWSCredentialsProviderChain(
    new ProfileCredentialsProvider(profileName.getOrElse("default")),
    new ContainerCredentialsProvider(),
    new InstanceProfileCredentialsProvider()
  )

  val panDomainSettings = new PanDomainAuthSettingsRefresher(
    domain = config.get[String]("auth.domain"),
    system = "archivehunter",
    actorSystem = actorSystem,
    awsCredentialsProvider = cpChain
  ) {
    override lazy val bucket: S3Bucket = new S3Bucket(cpChain){
      override val bucketName: String = config.get[String]("auth.panDomainBucket")
    }
  }
}
