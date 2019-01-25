package responses

import java.time.ZonedDateTime

case class ProxyFrameworkDeploymentInfo (region:String, stackId:String, stackName:String, stackStatus:String, templateDescription:String, creationTime:ZonedDateTime)

