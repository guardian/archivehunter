package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.services.securitytoken._
import javax.inject.{Inject, Singleton}

@Singleton
class STSClientManager @Inject() extends ClientManagerBase[AWSSecurityTokenService] {
  override def getClient(profileName: Option[String]): AWSSecurityTokenService =
    AWSSecurityTokenServiceClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()

  def getClientForRegion(profileName:Option[String],region:String): AWSSecurityTokenService =
    AWSSecurityTokenServiceClientBuilder.standard()
      .withCredentials(credentialsProvider(profileName))
      .withRegion(region)
      .build()
}
