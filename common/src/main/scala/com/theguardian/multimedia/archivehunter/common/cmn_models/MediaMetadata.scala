package com.theguardian.multimedia.archivehunter.common.cmn_models

import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}

/*
  "metadata": {
    "streams": [
      {
        "profile": "High",
        "sample_aspect_ratio": "1:1",
        "codec_tag": "0x31637661",
        "refs": 1,
        "extradata": "\\n00000000: 0164 0028 ffe1 0025 6764 0028 ac2b 4028  .d.(...%gd.(.+@(\\n00000010: 02dd 8088 0000 1f40 0006 1a87 0000 0301  .......@........\\n00000020: 6e36 0000 b71b 3779 707c 70ca 8001 0005  n6....7yp|p.....\\n00000030: 68ee 3cb0 00                             h.<..\\n",
        "codec_type": "video",
        "coded_height": 720,
        "bit_rate": "3000170",
        "codec_name": "h264",
        "duration": "239.000000",
        "is_avc": "1",
        "nb_frames": "5975",
        "codec_time_base": "1/50",
        "index": 0,
        "start_pts": 0,
        "width": 1280,
        "coded_width": 1280,
        "pix_fmt": "yuv420p",
        "chroma_location": "left",
        "tags": {
          "handler_name": "WowzaStreamingEngine",
          "creation_time": "2019-01-22 15:17:42",
          "language": "eng",
          "encoder": "WowzaStreamingEngine"
        },
        "r_frame_rate": "25/1",
        "start_time": "0.000000",
        "time_base": "1/90000",
        "codec_tag_string": "avc1",
        "duration_ts": 21510000,
        "codec_long_name": "H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10",
        "display_aspect_ratio": "16:9",
        "disposition": {
          "comment": 0,
          "forced": 0,
          "lyrics": 0,
          "default": 1,
          "dub": 0,
          "original": 0,
          "karaoke": 0,
          "clean_effects": 0,
          "attached_pic": 0,
          "visual_impaired": 0,
          "hearing_impaired": 0
        },
        "height": 720,
        "avg_frame_rate": "25/1",
        "level": 40,
        "bits_per_raw_sample": "8",
        "has_b_frames": 0,
        "nal_length_size": "4"
      },
      {
        "sample_fmt": "fltp",
        "codec_tag": "0x6134706d",
        "bits_per_sample": 0,
        "extradata": "\\n00000000: 1190                                     ..\\n",
        "codec_type": "audio",
        "channels": 2,
        "bit_rate": "125376",
        "codec_name": "aac",
        "duration": "238.998000",
        "nb_frames": "11203",
        "codec_time_base": "1/48000",
        "index": 1,
        "start_pts": 0,
        "profile": "LC",
        "tags": {
          "handler_name": "WowzaStreamingEngine",
          "creation_time": "2019-01-22 15:17:42",
          "language": "eng"
        },
        "r_frame_rate": "0/0",
        "start_time": "0.000000",
        "time_base": "1/90000",
        "codec_tag_string": "mp4a",
        "duration_ts": 21509820,
        "codec_long_name": "AAC (Advanced Audio Coding)",
        "disposition": {
          "comment": 0,
          "forced": 0,
          "lyrics": 0,
          "default": 1,
          "dub": 0,
          "original": 0,
          "karaoke": 0,
          "clean_effects": 0,
          "attached_pic": 0,
          "visual_impaired": 0,
          "hearing_impaired": 0
        },
        "avg_frame_rate": "0/0",
        "channel_layout": "stereo",
        "sample_rate": "48000"
      }
    ],
    "format": {
      "tags": {
        "major_brand": "f4v ",
        "creation_time": "2019-01-22 15:17:42",
        "compatible_brands": "isommp42m4v ",
        "minor_version": "0"
      },
      "nb_streams": 2,
      "start_time": "0.000000",
      "format_long_name": "QuickTime / MOV",
      "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
      "filename": "/tmp/mediafile",
      "bit_rate": "3128064",
      "nb_programs": 0,
      "duration": "239.013000",
      "probe_score": 100,
      "size": "93456017"
    }
  }
 */

case class StreamDisposition(comment:Boolean,forced:Boolean,lyrics:Boolean,default:Boolean,dub:Boolean,
                             original:Boolean,karaoke:Boolean,clean_effects:Boolean,attached_pic:Boolean,
                             visual_impaired:Boolean,hearing_impaired:Boolean)

case class MediaFormat (tags:Map[String,String],nb_streams:Int,start_time:Option[Double],format_long_name:String,
                        format_name:String, bit_rate: Double, nb_programs:Int, duration:Double, size:Long)

case class MediaStream(profile:Option[String],codec_type:Option[String],coded_width:Option[Int],coded_height:Option[Int],
                       bit_rate:Option[Double],codec_name:Option[String],duration:Option[Double],codec_time_base:Option[String],
                       index:Int, width:Option[Int],pix_fmt:Option[String],tags:Option[Map[String,String]],r_frame_rate:Option[String],
                       start_time:Option[Double],time_base:Option[String],codec_tag_string:Option[String],duration_ts:Option[Long],
                       codec_long_name:Option[String],display_aspect_ratio:Option[String],height:Option[Int],avg_frame_rate:Option[String],
                       level:Option[Int],bits_per_raw_sample:Option[Int],disposition:Option[StreamDisposition],
                       sample_fmt:Option[String],channels:Option[Int],channel_layout:Option[String],sample_rate:Option[Int])

case class MediaMetadata (
                         format:MediaFormat,
                         streams:Seq[MediaStream]
                         )

trait MediaMetadataEncoder {
  import io.circe.generic.auto._

  implicit val mediaFormatDecoder  = new Decoder[MediaFormat] {
    override def apply(c: HCursor): Result[MediaFormat] = for {
        tags <- c.downField("tags").as[Option[Map[String,String]]].map(_.getOrElse(Map()))
        nb_streams <- c.downField("nb_streams").as[Int]
        start_time <- c.downField("start_time").as[Option[Double]]
        format_long_name <- c.downField("format_long_name").as[Option[String]].map(_.getOrElse(""))
        format_name <- c.downField("format_name").as[Option[String]].map(_.getOrElse(""))
        bit_rate <- c.downField("bit_rate").as[Option[Double]].map(_.getOrElse(0.0))
        nb_programs <- c.downField("nb_programs").as[Option[Int]].map(_.getOrElse(0))
        duration <- c.downField("duration").as[Option[Double]].map(_.getOrElse(0.0))
        size <- c.downField("size").as[Option[Long]].map(_.getOrElse(0L))
      } yield MediaFormat(tags,nb_streams, start_time, format_long_name, format_name, bit_rate, nb_programs, duration, size)
  }


  implicit val streamDispositionDecoder = new Decoder[StreamDisposition] {
    override def apply(c: HCursor): Result[StreamDisposition] = for {
      comment <- c.downField("comment").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      forced <- c.downField("forced").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      lyrics <- c.downField("lyrics").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      default <- c.downField("default").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      dub <- c.downField("dub").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      original <- c.downField("original").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      karaoke <- c.downField("karaoke").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      clean_effects <- c.downField("clean_effects").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      attached_pic <- c.downField("attached_pic").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      visual_impaired <- c.downField("visual_impaired").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
      hearing_impaired <- c.downField("hearing_impaired").as[Option[Int]].map(_.map(value=>if(value==1) true else false).getOrElse(false))
    } yield StreamDisposition(comment, forced, lyrics, default, dub, original, karaoke, clean_effects, attached_pic, visual_impaired, hearing_impaired)
  }

  def safeGetField[A:Decoder](c:HCursor, fieldName:String) = c.downField(fieldName).as[A] match {
    case Left(err)=>Right(None)
    case Right(value)=>Right(Some(value))
  }

  implicit val mediaStreamDecoder = new Decoder[MediaStream] {
    override def apply(c: HCursor): Result[MediaStream] = for {
      profile <- c.downField("profile").as[Option[String]]
      codec_type <- c.downField("codec_type").as[Option[String]]
      coded_width <- c.downField("coded_width").as[Option[Int]]
      coded_height <- c.downField("coded_height").as[Option[Int]]
      bit_rate <- c.downField("bit_rate").as[Option[String]].map(_.map(_.toDouble))
      codec_name <- c.downField("codec_name").as[Option[String]]
      duration <- c.downField("duration").as[Option[String]].map(_.map(_.toDouble))
      codec_time_base <- c.downField("codec_time_base").as[Option[String]]
      index <- c.downField("index").as[Int]
      width <- c.downField("width").as[Option[Int]]
      pix_fmt <- c.downField("pix_fmt").as[Option[String]]
      tags <- c.downField("tags").as[Option[Map[String,String]]]
      r_frame_rate <- c.downField("r_frame_rate").as[Option[String]]
      start_time <- c.downField("start_time").as[Option[String]].map(_.map(_.toDouble))
      time_base <- c.downField("time_base").as[Option[String]]
      codec_tag_string <- c.downField("codec_tag_string").as[Option[String]]
      duration_ts <- c.downField("duration_ts").as[Option[Long]]
      codec_long_name <- c.downField("codec_long_name").as[Option[String]]
      display_aspect_ratio <- c.downField("display_aspect_ratio").as[Option[String]]
      height <- c.downField("height").as[Option[Int]]
      avg_frame_rate <- c.downField("avg_frame_rate").as[Option[String]]
      level <- c.downField("level").as[Option[Int]]
      //level <- safeGetField[String](c, "level")
      bits_per_raw_sample <- c.downField("bits_per_raw_sample").as[Option[Int]]
      disposition <- c.downField("disposition").as[Option[StreamDisposition]]
      sample_fmt <- c.downField("sample_fmt").as[Option[String]]
      channels <- c.downField("channels").as[Option[Int]]
      channel_layout <- c.downField("channel_layout").as[Option[String]]
      sample_rate <- c.downField("sample_rate").as[Option[Int]]
    } yield MediaStream(profile, codec_type, coded_width, coded_height, bit_rate, codec_name, duration, codec_time_base, index, width,
      pix_fmt,tags,r_frame_rate, start_time, time_base, codec_tag_string, duration_ts, codec_long_name, display_aspect_ratio, height,
      avg_frame_rate, level, bits_per_raw_sample, disposition, sample_fmt, channels, channel_layout, sample_rate)
  }
}