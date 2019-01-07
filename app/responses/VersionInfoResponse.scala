package responses

import java.time.ZonedDateTime

case class VersionInfoResponse (buildDate: Option[ZonedDateTime], buildBranch: Option[String], buildNumber: Option[Int])
