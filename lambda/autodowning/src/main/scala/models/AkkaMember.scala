package models

import java.net.URI

case class AkkaMember(node:URI, nodeUid:String, status:String, roles:Seq[String])
