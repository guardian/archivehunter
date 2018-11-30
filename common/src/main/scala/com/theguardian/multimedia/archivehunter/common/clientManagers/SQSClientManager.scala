package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import javax.inject.Inject

class SQSClientManager @Inject() extends ClientManagerBase[AmazonSQS] {
  override def getClient(profileName: Option[String]): AmazonSQS =
    AmazonSQSClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()
}
