package com.theguardian.multimedia.archivehunter.common.cmn_models

import org.apache.logging.log4j.LogManager


trait MediaMetadataMapConverters {

  /**
    * sometimes, these values come through with an explicit NULL value; the normal handling then converts this to Some(null)
    * which is incorrect and breaks stuff. This method converts Some(null) to None, and if there is a value then it typecasts
    * it to the final type provided as type parameter T
    * @param value Optional value to convert
    * @tparam T type to convert it to
    * @return an Option[T], which is guaranteed to be None if the value is actually null instead of None
    */
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
      MediaStream(
        safeMap[String](entry.get("profile")),
        safeMap[String](entry.get("codec_type")),
        safeMap[Int](entry.get("coded_width")),
        safeMap[Int](entry.get("coded_height")),
        safeMap[Double](entry.get("bit_rate")),
        safeMap[String](entry.get("codec_name")),
        safeMap[Double](entry.get("duration")),
        safeMap[String](entry.get("codec_time_base")),
        entry("index").asInstanceOf[Int],
        safeMap[Int](entry.get("width")),
        safeMap[String](entry.get("pix_fmt")),
        entry("tags").asInstanceOf[Map[String,String]],
        safeMap[String](entry.get("r_frame_rate")),
        safeMap[Double](entry.get("start_time")),
        safeMap[String](entry.get("time_base")),
        safeMap[String](entry.get("codec_tag_string")),
        None, //this field is causing ClassCastException(null) errors
        safeMap[String](entry.get("codec_long_name")),
        safeMap[String](entry.get("display_aspect_ratio")),
        safeMap[Int](entry.get("height")),
        safeMap[String](entry.get("avg_frame_rate")),
        safeMap[Int](entry.get("level")),
        safeMap[Int](entry.get("bits_per_raw_sample")),
        entry.get("disposition").map(value=>mappingToStreamDisposition(value.asInstanceOf[Map[String,Boolean]])),
        safeMap[String](entry.get("sample_fmt")),
        safeMap[Int](entry.get("channels")),
        safeMap[String](entry.get("channel_layout")),
        safeMap[Int](entry.get("sample_rate"))
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
      value("size").asInstanceOf[Long],
    )

  protected def mappingToMediaMetadata(value:Map[String,AnyVal]) = {
    MediaMetadata(
      mappingToMediaFormat(value("format").asInstanceOf[Map[String, AnyVal]]),
      mappingToStreamSeq(value("streams").asInstanceOf[Seq[Map[String, AnyVal]]])
    )
  }
}
