logLevel := Level.Warn

resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.11")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")
addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.1.18")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.20")
addSbtPlugin("com.typesafe.sbt" % "sbt-twirl" % "1.5.1")