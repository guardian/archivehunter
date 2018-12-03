package com.theguardian.multimedia.archivehunter
import com.amazonaws.services.elastictranscoder.AmazonElasticTranscoder
import com.amazonaws.services.elastictranscoder.model.{Preset, ReadPresetResult}
import com.theguardian.multimedia.archivehunter.common.cmn_services.ProxyGenerators
import org.apache.logging.log4j.LogManager
import org.specs2.mock.Mockito
import org.specs2.mutable._

class ProxyGeneratorsSpec extends Specification with Mockito{
  "ProxyGenerators.outputFilenameFor" should {
    "return an output filename with an updated file extension" in {
      val mockedClient = mock[AmazonElasticTranscoder]
      val logger = LogManager.getLogger(getClass)

      val mockedResponse = new ReadPresetResult().withPreset(new Preset().withContainer("mp3"))
      mockedClient.readPreset(any) returns mockedResponse

      val result = ProxyGenerators.outputFilenameFor("xxxxxx","/path/to/my/file.wav")(mockedClient, logger)
      result must beSuccessfulTry("/path/to/my/file.mp3")
    }

    "append the correct extension if the input filename has no extension" in {
      val mockedClient = mock[AmazonElasticTranscoder]
      val logger = LogManager.getLogger(getClass)

      val mockedResponse = new ReadPresetResult().withPreset(new Preset().withContainer("mp3"))
      mockedClient.readPreset(any) returns mockedResponse

      val result = ProxyGenerators.outputFilenameFor("xxxxxx","/path/to/my/file")(mockedClient, logger)
      result must beSuccessfulTry("/path/to/my/file.mp3")
    }

    "return an ETS exception as a failure" in {
      val mockedClient = mock[AmazonElasticTranscoder]
      val logger = LogManager.getLogger(getClass)

      val expectedException = new RuntimeException("my hovercraft is full of eels")
      val mockedResponse = new ReadPresetResult().withPreset(new Preset().withContainer("mp3"))
      mockedClient.readPreset(any) throws expectedException

      val result = ProxyGenerators.outputFilenameFor("xxxxxx","/path/to/my/file")(mockedClient, logger)
      result must beFailedTry(expectedException)
    }
  }
}
