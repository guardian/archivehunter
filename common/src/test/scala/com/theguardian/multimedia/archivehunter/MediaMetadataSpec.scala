package com.theguardian.multimedia.archivehunter
import com.theguardian.multimedia.archivehunter.common.cmn_models.{MediaMetadata, MediaMetadataEncoder}
import org.specs2.mutable.Specification
import io.circe.syntax._
import io.circe.generic.auto._

class MediaMetadataSpec extends Specification with MediaMetadataEncoder{
  "MediaMetadata class" should {
    "parse metadata from sample WAV files" in {
      val sample_meta = """{"streams": [{"index": 0, "sample_fmt": "s16", "codec_tag": "0x0001", "r_frame_rate": "0/0", "extradata": "\\n", "time_base": "1/48000", "codec_tag_string": "[1][0][0][0]", "codec_type": "audio", "disposition": {"comment": 0, "forced": 0, "lyrics": 0, "default": 0, "dub": 0, "original": 0, "karaoke": 0, "clean_effects": 0, "attached_pic": 0, "visual_impaired": 0, "hearing_impaired": 0}, "bit_rate": "1536000", "duration_ts": 114217470, "codec_long_name": "PCM signed 16-bit little-endian", "bits_per_sample": 16, "duration": "2379.530625", "codec_name": "pcm_s16le", "codec_time_base": "1/48000", "sample_rate": "48000", "channels": 2, "avg_frame_rate": "0/0"}], "format": {"tags": {"comment": "Cubase", "creation_time": "11:00:18", "originator_reference": "CCOOONNNNNNNNNNNN110018RRRRRRRRR", "coding_history": "", "time_reference": "4147152000", "encoded_by": "Cubase", "date": "2017-07-13", "umid": "0xD6B0627A36D2414E94709D96917EA2E100000000000000000000000000000000"}, "nb_streams": 1, "format_long_name": "WAV / WAVE (Waveform Audio)", "format_name": "wav", "filename": "/tmp/mediafile", "bit_rate": "1536007", "nb_programs": 0, "duration": "2379.530625", "probe_score": 99, "size": "456871964"}}"""

      val result = io.circe.parser.parse(sample_meta).flatMap(_.as[MediaMetadata])
      result must beRight

      val decodedData = result.right.get
      decodedData.format.duration mustEqual 2379.530625
      decodedData.format.format_long_name mustEqual "WAV / WAVE (Waveform Audio)"
      decodedData.format.start_time must beNone
    }

    "parse metadata from another sample WAV file" in {
      val sample_meta = """{"streams": [{"disposition": {"comment": 0, "forced": 0, "lyrics": 0, "default": 0, "visual_impaired": 0, "dub": 0, "karaoke": 0, "clean_effects": 0, "attached_pic": 0, "original": 0, "hearing_impaired": 0}, "index": 0, "sample_fmt": "s16", "codec_tag": "0x0001", "bits_per_sample": 16, "r_frame_rate": "0/0", "extradata": "\n", "time_base": "1/44100", "codec_tag_string": "[1][0][0][0]", "codec_type": "audio", "channels": 2, "bit_rate": "1411200", "duration_ts": 773245, "codec_long_name": "PCM signed 16-bit little-endian", "codec_name": "pcm_s16le", "duration": "17.533900", "sample_rate": "44100", "codec_time_base": "1/44100", "avg_frame_rate": "0/0"}], "format": {"nb_streams": 1, "format_long_name": "WAV / WAVE (Waveform Audio)", "format_name": "wav", "filename": "/tmp/mediafile", "bit_rate": "1411220", "nb_programs": 0, "duration": "17.533900", "probe_score": 99, "size": "3093024"}}"""

      val result = io.circe.parser.parse(sample_meta).flatMap(_.as[MediaMetadata])
      result must beRight

      val decodedData = result.right.get
      decodedData.format.duration mustEqual 17.533900
      decodedData.format.format_long_name mustEqual "WAV / WAVE (Waveform Audio)"
      decodedData.format.start_time must beNone
    }
  }
}
