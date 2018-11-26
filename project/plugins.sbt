logLevel := Level.Warn

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.6.15")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")
addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.1.8")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")