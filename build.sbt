import sbt._
import Keys._
import com.typesafe.sbt.packager.docker
import com.typesafe.sbt.packager.docker._

enablePlugins(RiffRaffArtifact, DockerPlugin, SystemdPlugin)

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" % "scala-java8-compat" % "pvp"
scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-language:postfixOps")
scalaVersion := "2.13.9"

val akkaVersion = "2.6.18"
val akkaClusterVersion = "1.1.3"
val elastic4sVersion = "6.7.8"
val awsSdkVersion = "1.12.153"
val awsSdk2Version = "2.20.162"
val jacksonVersion = "2.15.0"
val jacksonCoreVersion = "2.15.0"

lazy val commonSettings = Seq(
  version := "1.0",
  scalaVersion := "2.13.8",
  libraryDependencies ++= Seq("org.apache.logging.log4j" % "log4j-core" % "2.17.1",
    "com.beust" % "jcommander" % "1.75", //snyk identified as vulnerable
    "org.apache.logging.log4j" % "log4j-api" % "2.17.1",
    "com.amazonaws" % "aws-java-sdk-elastictranscoder"% awsSdkVersion,
    "com.amazonaws" % "aws-java-sdk-sqs"% awsSdkVersion,
    "com.dripower" %% "play-circe" % "2812.0",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.0",
    "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion  exclude("com.fasterxml.jackson.module","jackson-module-scala"),
    "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-http-streams" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
    "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "software.amazon.awssdk" % "dynamodb" % awsSdk2Version,
    "software.amazon.awssdk" % "s3" % awsSdk2Version,
    "software.amazon.awssdk" % "aws-cbor-protocol" % awsSdk2Version,
    "com.lightbend.akka" %% "akka-stream-alpakka-dynamodb" % "2.0.2",
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % "2.0.2",
    "org.scanamo" %% "scanamo-alpakka" % "1.0.0-M16",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonVersion,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonCoreVersion,
    "com.google.guava" % "guava" % "32.0.0-jre",
      specs2 % Test)
)

lazy val `archivehunter` = (project in file("."))
  .enablePlugins(PlayScala)
  .dependsOn(common)
  .settings(commonSettings,
    libraryDependencies ++= Seq(
      "com.dripower" %% "play-circe" % "2812.0",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.13.1",
      "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion exclude("com.fasterxml.jackson.module","jackson-module-scala"),
      "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
      "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonCoreVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
      "com.lightbend.akka" %% "akka-stream-alpakka-s3" % "3.0.2",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaClusterVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaClusterVersion,
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % akkaClusterVersion,
      "com.lightbend.akka.discovery" %% "akka-discovery-aws-api" % akkaClusterVersion,
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
      "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.7",
      "com.typesafe.akka" %% "akka-http-xml" % "10.2.7",
      "com.typesafe.akka" %% "akka-http" % "10.2.7",
      "com.nimbusds" % "nimbus-jose-jwt" % "9.18",
      "com.gu" % "kinesis-logback-appender" % "2.0.3",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
      "org.apache.logging.log4j" % "log4j-api" % "2.17.1",
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
      "io.sentry" % "sentry-logback" % "6.25.2",
      guice, ehcache, ws)
  )

val lambdaDeps = Seq(
)

val circeVersion = "0.12.0-M3" //required for compatibility with elastic4s-circe

lazy val common = (project in file("common"))
  .settings(commonSettings,
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk-s3" % awsSdkVersion,
      "org.scanamo" %% "scanamo-alpakka" % "1.0.0-M16",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scanamo" %% "scanamo" % "1.0.0-M16",
      "com.google.inject" % "guice" % "4.2.3",  //keep this in sync with play version
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
    "org.apache.logging.log4j" % "log4j-core" % "2.17.1",
    "org.apache.logging.log4j" % "log4j-api" % "2.17.1",
    "org.apache.logging.log4j" % "log4j-1.2-api" % "2.17.1",
    "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
    "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
    "com.amazonaws" % "aws-java-sdk-lambda" % awsSdkVersion,
    "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
    "com.amazonaws" % "aws-lambda-java-core" % "1.0.0",
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-protobuf" % akkaVersion,
    "com.typesafe.akka" %% "akka-protobuf-v3" % akkaVersion,
  ),
  assembly / assemblyJarName:= "inputLambda.jar",
  assembly / assemblyMergeStrategy:= {
    case PathList("javax", "servlet", xs @ _*)         => MergeStrategy.first
    case PathList(ps @ _*) if ps.last endsWith ".html" => MergeStrategy.first
    case "application.conf" => MergeStrategy.concat
    case PathList("META-INF","org","apache","logging","log4j","core","config","plugins","Log4j2Plugins.dat") => MergeStrategy.last
    case PathList(ps @ _*) if ps.last == "module-info.class" => MergeStrategy.discard
    case PathList(ps @ _*) if ps.last=="mime.types" => MergeStrategy.last
    case meta(_)=>MergeStrategy.discard
    case x=>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
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
      "com.sandinh" %% "akka-guice" % "3.3.0"
    ),
    version := sys.props.getOrElse("build.number","DEV"),
    dockerUsername  := sys.props.get("docker.username"),
    dockerRepository := Some("andyg42"),
    dockerPermissionStrategy := DockerPermissionStrategy.None,
    Docker / packageName := "andyg42/archivehunter-proxystats",
    packageName := "archivehunter-proxystats",
    dockerAlias := docker.DockerAlias(sys.props.get("docker.host"),sys.props.get("docker.username"),"proxy-stats-gathering",Some(sys.props.getOrElse("build.number","DEV"))),
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      Cmd("RUN", "chown -R 1001 /opt/docker"),
      Cmd("USER", "demiourgos728")
    )
  )

lazy val autoDowningLambda = (project in file("lambda/autodowning")).settings(commonSettings, name:="autoDowningLambda")
  .settings(commonSettings,
    libraryDependencies :=Seq(
      "com.amazonaws" % "aws-lambda-java-events" % "3.11.0",
      "software.amazon.awssdk" % "ec2" % awsSdk2Version,
      "software.amazon.awssdk" % "url-connection-client" % awsSdk2Version,
      "com.amazonaws" % "aws-lambda-java-core" % "1.0.0",
      //manual dependencies from common so that we don't pull in too much un-needed stuff
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scanamo" %% "scanamo" % "1.0.0-M16",
      specs2 % Test
    ),
    assembly / assemblyJarName := "autoDowningLambda.jar",
    assembly / assemblyMergeStrategy := {
      case PathList(ps @ _*) if ps.last=="module-info.class" => MergeStrategy.discard
      case meta(_) => MergeStrategy.discard
      case PathList(ps @ _*) if ps.last=="mime.types" => MergeStrategy.last
      case x=>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

val jsTargetDir = "target/riffraff/packages"

riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffManifestProjectName := "multimedia:ArchiveHunter"
riffRaffArtifactResources := Seq(
  (archivehunter / Debian / packageBin).value -> s"archivehunter-webapp/${(archivehunter / name).value}.deb",
  (inputLambda / Universal / assembly).value -> s"archivehunter-input-lambda/${(inputLambda / Universal / assembly).value.getName}",
  (autoDowningLambda / Universal / assembly).value -> s"archivehunter-autodowning-lambda/${(autoDowningLambda / Universal / assembly).value.getName}",
  (archivehunter / baseDirectory).value / "riff-raff.yaml" -> "riff-raff.yaml",
)


resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

Test / unmanagedResourceDirectories +=  { baseDirectory ( _ /"target/web/public/test" ).value }

