import com.theguardian.multimedia.archivehunter.common.ProxyTypeEncoder
import com.theguardian.multimedia.archivehunter.common.cmn_models._
import models.{JobReportNew, JobReportStatusEncoder}
import org.specs2.mutable.Specification
import io.circe.syntax._
import io.circe.generic.auto._

class DecodeMetaSpec extends Specification with JobReportStatusEncoder with ProxyTypeEncoder with MediaMetadataEncoder {
  val sampleData="""{"status": "SUCCESS", "input": "s3://mybucket/TestDemoForLawrence.mp4", "jobId": "78b2a714-8690-420a-af24-0321fc4c1a6f", "metadata": {"streams": [{"profile": "High", "sample_aspect_ratio": "1:1", "codec_tag": "0x31637661", "refs": 1, "extradata": "\\n00000000: 0164 0028 ffe1 0025 6764 0028 ac2b 4028  .d.(...%gd.(.+@(\\n00000010: 02dd 8088 0000 1f40 0006 1a87 0000 0301  .......@........\\n00000020: 6e36 0000 b71b 3779 707c 70ca 8001 0005  n6....7yp|p.....\\n00000030: 68ee 3cb0 00                             h.<..\\n", "codec_type": "video", "coded_height": 720, "bit_rate": "3000170", "codec_name": "h264", "duration": "239.000000", "is_avc": "1", "nb_frames": "5975", "codec_time_base": "1/50", "index": 0, "start_pts": 0, "width": 1280, "coded_width": 1280, "pix_fmt": "yuv420p", "chroma_location": "left", "tags": {"handler_name": "WowzaStreamingEngine", "creation_time": "2019-01-22 15:17:42", "language": "eng", "encoder": "WowzaStreamingEngine"}, "r_frame_rate": "25/1", "start_time": "0.000000", "time_base": "1/90000", "codec_tag_string": "avc1", "duration_ts": 21510000, "codec_long_name": "H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10", "display_aspect_ratio": "16:9", "disposition": {"comment": 0, "forced": 0, "lyrics": 0, "default": 1, "dub": 0, "original": 0, "karaoke": 0, "clean_effects": 0, "attached_pic": 0, "visual_impaired": 0, "hearing_impaired": 0}, "height": 720, "avg_frame_rate": "25/1", "level": 40, "bits_per_raw_sample": "8", "has_b_frames": 0, "nal_length_size": "4"}, {"sample_fmt": "fltp", "codec_tag": "0x6134706d", "bits_per_sample": 0, "extradata": "\\n00000000: 1190                                     ..\\n", "codec_type": "audio", "channels": 2, "bit_rate": "125376", "codec_name": "aac", "duration": "238.998000", "nb_frames": "11203", "codec_time_base": "1/48000", "index": 1, "start_pts": 0, "profile": "LC", "tags": {"handler_name": "WowzaStreamingEngine", "creation_time": "2019-01-22 15:17:42", "language": "eng"}, "r_frame_rate": "0/0", "start_time": "0.000000", "time_base": "1/90000", "codec_tag_string": "mp4a", "duration_ts": 21509820, "codec_long_name": "AAC (Advanced Audio Coding)", "disposition": {"comment": 0, "forced": 0, "lyrics": 0, "default": 1, "dub": 0, "original": 0, "karaoke": 0, "clean_effects": 0, "attached_pic": 0, "visual_impaired": 0, "hearing_impaired": 0}, "avg_frame_rate": "0/0", "channel_layout": "stereo", "sample_rate": "48000"}], "format": {"tags": {"major_brand": "f4v ", "creation_time": "2019-01-22 15:17:42", "compatible_brands": "isommp42m4v ", "minor_version": "0"}, "nb_streams": 2, "start_time": "0.000000", "format_long_name": "QuickTime / MOV", "format_name": "mov,mp4,m4a,3gp,3g2,mj2", "filename": "/tmp/mediafile", "bit_rate": "3128064", "nb_programs": 0, "duration": "239.013000", "probe_score": 100, "size": "93456017"}}}"""

  "apply method" should {
    "successfully decode sample data" in {
      val jobData = io.circe.parser.parse(sampleData).flatMap(_.as[JobReportNew])
      jobData must beRight
      jobData.right.get.metadata must beSome
      val meta = jobData.right.get.metadata.get
      meta mustEqual MediaMetadata(
        MediaFormat(
          tags = Map("major_brand"->"f4v ","creation_time"->"2019-01-22 15:17:42","compatible_brands"->"isommp42m4v ","minor_version"->"0"),
          nb_streams = 2,
          start_time = Some(0.00),
          format_long_name = "QuickTime / MOV",
          format_name = "mov,mp4,m4a,3gp,3g2,mj2",
          bit_rate = 3128064.0,
          nb_programs = 0,
          duration = 239.013000,
          size = 93456017L
        ),
        Seq(
          MediaStream(
            Some("High"),
            Some("video"),
            Some(1280),
            Some(720),
            Some(3000170.0),
            Some("h264"),
            Some(239.000000),
            Some("1/50"),
            0,
            Some(1280),
            Some("yuv420p"),
            Some(Map("handler_name"->"WowzaStreamingEngine","creation_time"->"2019-01-22 15:17:42","language"->"eng","encoder"->"WowzaStreamingEngine")),
            Some("25/1"),
            Some(0.000000),
            Some("1/90000"),
            Some("avc1"),
            Some(21510000L),
            Some("H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10"),
            Some("16:9"),
            Some(720),
            Some("25/1"),
            Some(40),
            Some(8),
            Some(StreamDisposition(false,false,false,true,false,false,false,false,false,false,false)),
            None,
            None,
            None,
            None
          ),
          MediaStream(
            Some("LC"),
            Some("audio"),
            None,
            None,
            Some(125376),
            Some("aac"),
            Some(238.998000),
            Some("1/48000"),
            1,
            None,
            None,
            Some(Map("handler_name"->"WowzaStreamingEngine","creation_time"->"2019-01-22 15:17:42","language"->"eng")),
            Some("0/0"),
            Some(0.000),
            Some("1/90000"),
            Some("mp4a"),
            Some(21509820),
            Some("AAC (Advanced Audio Coding)"),
            None,
            None,
            Some("0/0"),
            None,
            None,
            Some(StreamDisposition(false,false,false,true,false,false,false,false,false,false,false)),
            Some("fltp"),
            Some(2),
            Some("stereo"),
            Some(48000)
          )
        )
      )
    }
  }
}
