package com.theguardian.multimedia.archivehunter.common.cmn_models

import org.apache.logging.log4j.LogManager


trait MediaMetadataMapConverters {
  protected def safeMap[T](value:Option[Any]) = value match {
    case None=>None
    case Some(null)=>None
    case Some(actualValue)=>Some(actualValue.asInstanceOf[T])
  }

  protected def mappingToStreamDisposition(value:Map[String,Boolean]) =
    StreamDisposition(value.getOrElse("comment", false),value.getOrElse("forced", false),value.getOrElse("lyrics", false),
      value.getOrElse("default", false),value.getOrElse("dub", false),value.getOrElse("original", false),
      value.getOrElse("karaoke", false),value.getOrElse("clean_effects", false),value.getOrElse("attached_pic", false),
      value.getOrElse("visual_impaired", false),value.getOrElse("hearing_impaired", false))

  protected def mappingToStreamSeq(value:Seq[Map[String,AnyVal]]) =
    value.map(entry=>
      MediaStream(safeMap[String](entry.get("profile")),
        entry.get("codec_type").map(_.asInstanceOf[String]),
        entry.get("codec_width").map(_.asInstanceOf[Int]),
        entry.get("codec_height").map(_.asInstanceOf[Int]),
        entry.get("bit_rate").map(_.asInstanceOf[Double]),
        entry.get("codec_name").map(_.asInstanceOf[String]),
        entry.get("duration").map(_.asInstanceOf[Double]),
        entry.get("codec_time_base").map(_.asInstanceOf[String]),
        entry("index").asInstanceOf[Int],
        entry.get("width").map(_.asInstanceOf[Int]),
        entry.get("pix_fmt").map(_.asInstanceOf[String]),
        entry("tags").asInstanceOf[Map[String,String]],
        entry.get("r_frame_rate").map(_.asInstanceOf[String]),
        entry.get("start_time").map(_.asInstanceOf[Double]),
        entry.get("time_base").map(_.asInstanceOf[String]),
        entry.get("codec_tag_string").map(_.asInstanceOf[String]),
        entry.get("duration_ts").map(_.asInstanceOf[Long]),
        entry.get("codec_long_name").map(_.asInstanceOf[String]),
        entry.get("display_aspect_ratio").map(_.asInstanceOf[String]),
        entry.get("height").map(_.asInstanceOf[Int]),
        entry.get("avg_frame_rate").map(_.asInstanceOf[String]),
        entry.get("level").map(_.asInstanceOf[Int]),
        entry.get("bits_per_sample").map(_.asInstanceOf[Int]),
        entry.get("disposition").map(value=>mappingToStreamDisposition(value.asInstanceOf[Map[String,Boolean]])),
        entry.get("sample_fmt").map(_.asInstanceOf[String]),
        entry.get("channels").map(_.asInstanceOf[Int]),
        entry.get("channel_layout").map(_.asInstanceOf[String]),
        entry.get("sample_rate").map(_.asInstanceOf[Int])
      )
    )
  
  protected def mappingToMediaFormat(value:Map[String,AnyVal]) =
    MediaFormat(
      value("tags").asInstanceOf[Map[String,String]],
      value("nb_streams").asInstanceOf[Int],
      value("start_time").asInstanceOf[Double],
      value("format_long_name").asInstanceOf[String],
      value("format_name").asInstanceOf[String],
      value("bit_rate").asInstanceOf[Double],
      value("nb_programs").asInstanceOf[Int],
      value("duration").asInstanceOf[Double],
      value("size").asInstanceOf[Int],
    )

  protected def mappingToMediaMetadata(value:Map[String,AnyVal]) = {
    println(value.toString)
    MediaMetadata(
      mappingToMediaFormat(value("format").asInstanceOf[Map[String, AnyVal]]),
      mappingToStreamSeq(value("streams").asInstanceOf[Seq[Map[String, AnyVal]]])
    )
  }
}
