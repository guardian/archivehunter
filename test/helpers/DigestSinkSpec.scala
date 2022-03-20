package helpers

import TestFileMove.AkkaTestkitSpecs2Support
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.apache.commons.codec.binary.Hex
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try

class DigestSinkSpec extends Specification {
  "DigestSink" should {
    "calculate a known md5 digest" in new AkkaTestkitSpecs2Support {
      val cs = Source
        .single(ByteString("Peter piper picked a pick of pickled pepper"))
        .runWith(DigestSink("MD5"))
        .map(rawChecksim=>Hex.encodeHexString(rawChecksim.toByteBuffer))

      val result = Try { Await.result(cs, 10.seconds) }
      result must beASuccessfulTry("92bdd6fd61d27fbd873d7daf9aac638e")
    }

    "calculate a known SHA-1 digest" in new AkkaTestkitSpecs2Support {
      val cs = Source
        .single(ByteString("Peter piper picked a pick of pickled pepper"))
        .runWith(DigestSink("SHA1"))
        .map(rawChecksim=>Hex.encodeHexString(rawChecksim.toByteBuffer))

      val result = Try { Await.result(cs, 10.seconds) }
      result must beASuccessfulTry("9ef5485a4b69262cc3d1338ac385fc085d8af212")
    }

    "calculate a known SHA-256 digest" in new AkkaTestkitSpecs2Support {
      val cs = Source
        .single(ByteString("Peter piper picked a pick of pickled pepper"))
        .runWith(DigestSink("SHA-256"))
        .map(rawChecksim=>Hex.encodeHexString(rawChecksim.toByteBuffer))

      val result = Try { Await.result(cs, 10.seconds) }
      result must beASuccessfulTry("301755e290312cbb430b63b29c1df2151d4470994177b75c09abbad3b118ef6e")
    }
  }
}
