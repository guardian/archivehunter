package com.theguardian.multimedia.archivehunter.common.clientManagers

import com.amazonaws.services.elastictranscoder.{AmazonElasticTranscoder, AmazonElasticTranscoderClientBuilder}
import javax.inject.Singleton

@Singleton
class ETSClientManager extends ClientManagerBase[AmazonElasticTranscoder] {
  override def getClient(profileName: Option[String]): AmazonElasticTranscoder =
    AmazonElasticTranscoderClientBuilder.standard().withCredentials(credentialsProvider(profileName)).build()
}
