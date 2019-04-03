package com.theguardian.multimedia.archivehunter.common.cmn_models

import java.time.ZonedDateTime

case class ProblemItemCount(scanStart:ZonedDateTime, scanFinish:Option[ZonedDateTime], proxiedCount:Int, partialCount:Int, unProxiedCount:Int, notNeededCount:Int, dotFile:Int, glacier:Int)
