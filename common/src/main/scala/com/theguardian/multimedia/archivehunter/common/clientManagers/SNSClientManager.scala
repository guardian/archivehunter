package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider
import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import javax.inject.{Inject, Singleton}

import scala.util.Try

@Singleton
class SNSClientManager @Inject() extends ClientManagerBase [AmazonSNS]{
  override def getClient(profileName: Option[String]): AmazonSNS =
    AmazonSNSClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()

  def getClientForRegion(profileName:Option[String], region:String):AmazonSNS =
    AmazonSNSClientBuilder.standard().withRegion(region).withCredentials(credentialsProvider(profileName)).build()


  def getTemporaryClient(region:String, roleArn:String, roleDuration:Int=900)
                                  (implicit stsClient:AWSSecurityTokenService):Try[AmazonSNS] = Try {

    val builder = new STSAssumeRoleSessionCredentialsProvider.Builder(roleArn,"archiveHunterSetup")
      .withRoleSessionDurationSeconds(roleDuration)
      .withStsClient(stsClient)

    AmazonSNSClientBuilder.standard()
      .withRegion(region)
      .withCredentials(builder.build())
      .build()
  }
}
