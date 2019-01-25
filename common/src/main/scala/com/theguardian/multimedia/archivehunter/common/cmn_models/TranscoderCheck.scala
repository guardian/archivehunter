package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime

case class TranscoderCheck(checkedAt:ZonedDateTime, status:JobStatus.Value, log:Option[String])
