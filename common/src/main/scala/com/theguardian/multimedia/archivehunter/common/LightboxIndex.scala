package com.theguardian.multimedia.archivehunter.common

import java.time.ZonedDateTime

case class LightboxIndex(owner:String, avatarUrl:Option[String], addedAt:ZonedDateTime)
