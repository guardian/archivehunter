package helpers

import akka.actor.ActorSystem
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProviderChain, BasicAWSCredentials, ContainerCredentialsProvider, InstanceProfileCredentialsProvider}
import com.amazonaws.internal.StaticCredentialsProvider
import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import com.gu.pandomainauth.model.PanDomainAuthSettings
import com.gu.pandomainauth.service.S3Bucket

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}

trait InjectableRefresher {
  val panDomainSettings:PanDomainAuthSettingsRefresher
}

@Singleton
class InjectableRefresherMock @Inject() (config:Configuration, actorSystem: ActorSystem) extends InjectableRefresher {
  private val cpChain = new AWSCredentialsProviderChain(
    new StaticCredentialsProvider(new BasicAWSCredentials("key","secret"))
  )

  override lazy val panDomainSettings: PanDomainAuthSettingsRefresher = new PanDomainAuthSettingsRefresher(
    domain = config.get[String]("auth.domain"),
    system = "archivehunter",
    actorSystem = actorSystem,
    awsCredentialsProvider = cpChain
  ) {
    override def settings: PanDomainAuthSettings = PanDomainAuthSettings(Map.empty)
  }
}

@Singleton
class InjectableRefresherImpl @Inject() (config:Configuration, actorSystem: ActorSystem) extends InjectableRefresher {
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
