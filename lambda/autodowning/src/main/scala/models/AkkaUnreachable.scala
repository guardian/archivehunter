package models

import java.net.URI

case class AkkaUnreachable (node:URI, observedBy:Seq[URI])
