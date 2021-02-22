import sbt._
import Keys._
import com.typesafe.sbt.packager.docker
import com.typesafe.sbt.packager.docker._

enablePlugins(RiffRaffArtifact, DockerPlugin, SystemdPlugin)

scalaVersion := "2.12.13"

val akkaVersion = "2.5.18"
val akkaClusterVersion = "1.0.9"
val elastic4sVersion = "6.0.4"
val awsSdkVersion = "1.11.959"
val jacksonVersion = "2.9.10"
val jacksonCoreVersion = "2.9.10.8"

lazy val commonSettings = Seq(
  version := "1.0",
  scalaVersion := "2.12.2",
  libraryDependencies ++= Seq("org.apache.logging.log4j" % "log4j-core" % "2.13.2",
    "com.beust" % "jcommander" % "1.75", //snyk identified as vulnerable
    "org.apache.logging.log4j" % "log4j-api" % "2.8.2",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
    "com.amazonaws" % "aws-java-sdk-s3" % awsSdkVersion,
    "com.amazonaws" % "aws-java-sdk-elastictranscoder"% awsSdkVersion,
    "com.amazonaws" % "aws-java-sdk-sqs"% awsSdkVersion,
    "com.dripower" %% "play-circe" % "2610.0",
    "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-http-streams" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
    "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
    "com.lightbend.akka" %% "akka-stream-alpakka-dynamodb" % "0.20",
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % "0.20",
    "com.gu" %% "scanamo-alpakka" % "1.0.0-M8",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonCoreVersion,
    "com.google.guava" % "guava" % "30.0-jre",
      specs2 % Test)
)

lazy val `archivehunter` = (project in file("."))
  .enablePlugins(PlayScala)
  .dependsOn(common)
  .settings(commonSettings,
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-guice" % "2.6.25",
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
      "com.dripower" %% "play-circe" % "2610.0",
      "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
      "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
      "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaClusterVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaClusterVersion,
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % akkaClusterVersion,
      "com.lightbend.akka.discovery" %% "akka-discovery-aws-api" % akkaClusterVersion,
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-agent" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
      "com.gu" % "kinesis-logback-appender" % "2.0.1",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % "2.11.4",  //fix vulnerable dependency for kinesis-logback-appender
      "org.apache.logging.log4j" % "log4j-api" % "2.8.2",
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
      "com.gu" %% "panda-hmac-play_2-6" % "1.3.1",
      "io.sentry" % "sentry-logback" % "1.7.2",
        jdbc, ehcache, ws)
  )

val lambdaDeps = Seq(
  "com.amazonaws" % "aws-lambda-java-log4j2" % "1.0.0"
)
val circeVersion = "0.9.3"

lazy val common = (project in file("common"))
  .settings(commonSettings,
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk-s3" % awsSdkVersion,
      "com.gu" %% "scanamo-alpakka" % "1.0.0-M8",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-java8" % circeVersion,
      "com.gu" %% "scanamo" % "1.0.0-M8",
      "com.google.inject" % "guice" % "4.1.0",  //keep this in sync with play version
      "com.amazonaws" % "aws-java-sdk-sns" % awsSdkVersion,
      "com.amazonaws" % "aws-java-sdk-sts" % awsSdkVersion,
      "com.amazonaws" % "aws-java-sdk-cloudformation" % awsSdkVersion,
    )
  )

val meta = """META.INF(.)*""".r

lazy val inputLambda = (project in file("lambda/input"))
  .dependsOn(common)
  .settings(commonSettings,
  // https://mvnrepository.com/artifact/com.amazonaws/aws-java-sdk-lambda
  libraryDependencies ++= Seq(
    "com.amazonaws" % "aws-java-sdk-lambda" % awsSdkVersion,
    "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
    "com.amazonaws" % "aws-lambda-java-core" % "1.0.0",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
    "com.amazonaws" % "aws-lambda-java-log4j2" % "1.0.0",
  ),
  assemblyJarName in assembly := "inputLambda.jar",
  assemblyMergeStrategy in assembly := {
    case PathList("javax", "servlet", xs @ _*)         => MergeStrategy.first
    case PathList(ps @ _*) if ps.last endsWith ".html" => MergeStrategy.first
    case "application.conf" => MergeStrategy.concat
      //META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat
    case PathList("META-INF","org","apache","logging","log4j","core","config","plugins","Log4j2Plugins.dat") => MergeStrategy.last
    case meta(_)=>MergeStrategy.discard
    case x=>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)

  }
)

lazy val proxyStatsGathering = (project in file("ProxyStatsGathering"))
  .dependsOn(common)
  .enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin)
  .settings(commonSettings,
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk-lambda" % awsSdkVersion,
      "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
      "com.amazonaws" % "aws-lambda-java-core" % "1.0.0",
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
      "com.amazonaws" % "aws-lambda-java-log4j2" % "1.0.0",
      "com.sandinh" %% "akka-guice" % "3.2.0"
    ),
    version := sys.props.getOrElse("build.number","DEV"),
    dockerUsername  := sys.props.get("docker.username"),
    dockerRepository := Some("andyg42"),
    dockerPermissionStrategy := DockerPermissionStrategy.None,
    packageName in Docker := "andyg42/archivehunter-proxystats",
    packageName := "archivehunter-proxystats",
//    dockerBaseImage := "openjdk:8-jdk-alpine",
    dockerAlias := docker.DockerAlias(sys.props.get("docker.host"),sys.props.get("docker.username"),"proxy-stats-gathering",Some(sys.props.getOrElse("build.number","DEV"))),
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      Cmd("RUN", "chown -R 1001 /opt/docker"),
      Cmd("USER", "demiourgos728")
    )
  )

lazy val autoDowningLambda = (project in file("lambda/autodowning")).settings(commonSettings, name:="autoDowningLambda")
  .dependsOn(common)
  .settings(commonSettings,
    libraryDependencies :=Seq(
      "com.amazonaws" % "aws-java-sdk-lambda" % awsSdkVersion,
      "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
      "com.amazonaws" % "aws-java-sdk-events" % awsSdkVersion,
      "com.amazonaws" % "aws-java-sdk-ec2" % awsSdkVersion,
      "com.amazonaws" % "aws-lambda-java-core" % "1.0.0",
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
      "ch.qos.logback"          %  "logback-classic" % "1.2.3",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.10.5",
      specs2 % Test
    ),
    assemblyJarName in assembly := "autoDowningLambda.jar",
  )

val jsTargetDir = "target/riffraff/packages"

riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffManifestProjectName := "multimedia:ArchiveHunter"
riffRaffArtifactResources := Seq(
  (packageBin in Debian in archivehunter).value -> s"archivehunter-webapp/${(name in archivehunter).value}.deb",
  (assembly in Universal in inputLambda).value -> s"archivehunter-input-lambda/${(assembly in Universal in inputLambda).value.getName}",
  (assembly in Universal in autoDowningLambda).value -> s"archivehunter-autodowning-lambda/${(assembly in Universal in autoDowningLambda).value.getName}",
  (baseDirectory in Global in archivehunter).value / "riff-raff.yaml" -> "riff-raff.yaml",
)


resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

unmanagedResourceDirectories in Test +=  { baseDirectory ( _ /"target/web/public/test" ).value }

