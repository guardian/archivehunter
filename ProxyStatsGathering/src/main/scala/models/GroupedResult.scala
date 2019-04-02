package models

object ProxyResult extends Enumeration {
  val Proxied, Partial, Unproxied, NotNeeded, DotFile, GlacierClass = Value
}

case class GroupedResult (fileId:String, result:ProxyResult.Value)
