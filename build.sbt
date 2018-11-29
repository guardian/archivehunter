import sbt._
import Keys._

enablePlugins(RiffRaffArtifact, JDebPackaging, SystemdPlugin)

//libraryDependencies += "org.vafer" % "jdeb" % "1.3" artifacts (Artifact("jdeb", "jar", "jar"))

val elastic4sVersion = "6.0.4"
val awsSdkVersion = "1.11.346"

lazy val commonSettings = Seq(
  version := "1.0",
  scalaVersion := "2.12.2",
  libraryDependencies ++= Seq("org.apache.logging.log4j" % "log4j-core" % "2.8.2",
    "org.apache.logging.log4j" % "log4j-api" % "2.8.2",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
    "com.amazonaws" % "aws-java-sdk-s3" % awsSdkVersion,
    "com.amazonaws" % "aws-java-sdk-elastictranscoder"% awsSdkVersion,
    "com.dripower" %% "play-circe" % "2610.0",
    "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-http-streams" % elastic4sVersion,
    "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
    "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
    "com.lightbend.akka" %% "akka-stream-alpakka-dynamodb" % "0.20",
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % "0.20",
    "com.gu" %% "scanamo-alpakka" % "1.0.0-M8",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.9.7",
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.7",
      specs2 % Test)
)

scalaVersion := "2.12.2"

val akkaVersion = "2.5.18"

lazy val `archivehunter` = (project in file("."))
  .enablePlugins(PlayScala)
  .dependsOn(common)
  .settings(commonSettings,
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-guice" % "2.6.15",
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
      "com.dripower" %% "play-circe" % "2610.0",
      "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
      "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
      "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "0.20.0",
      "com.lightbend.akka.discovery" %% "akka-discovery-dns" % "0.20.0",
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % "0.20.0",
      "com.lightbend.akka.discovery" %% "akka-discovery-aws-api" % "0.18.0",
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      // Only if you are using Akka Testkit
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
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
      "com.amazonaws" % "aws-java-sdk-ecs" % awsSdkVersion
    )
  )

lazy val inputLambda = (project in file("lambda/input"))
  .dependsOn(common)
  .settings(commonSettings,
  // https://mvnrepository.com/artifact/com.amazonaws/aws-java-sdk-lambda
  libraryDependencies ++= Seq(
    "com.amazonaws" % "aws-java-sdk-lambda" % awsSdkVersion,
    "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
    "com.amazonaws" % "aws-lambda-java-core" % "1.0.0",
//    "software.amazon.awssdk" % "lambda" % awsversion,
//    "software.amazon.awssdk" % "core" % awsversion,
//    "com.amazonaws" % "aws-lambda-java-events" % "2.1.0",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0"
  ),
  assemblyJarName in assembly := "inputLambda.jar",
)

val jsTargetDir = "target/riffraff/packages"

riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffManifestProjectName := "multimedia:ArchiveHunter"
riffRaffArtifactResources := Seq(
  (packageBin in Debian in archivehunter).value -> s"archivehunter-webapp/${(name in archivehunter).value}.deb",
  (assembly in Universal in inputLambda).value -> s"archivehunter-input-lambda/${(assembly in Universal in inputLambda).value.getName}",
//  (packageBin in Universal in expirer).value -> s"${(name in expirer).value}/${(packageBin in Universal in expirer).value.getName}",
//  (packageBin in Universal in scheduler).value -> s"${(name in scheduler).value}/${(packageBin in Universal in scheduler).value.getName}",
//  (baseDirectory in Global in app).value / s"$plutoMessageIngestion/$jsTargetDir/$plutoMessageIngestion/$plutoMessageIngestion.zip" -> s"$plutoMessageIngestion/$plutoMessageIngestion.zip",
  (baseDirectory in Global in archivehunter).value / "riff-raff.yaml" -> "riff-raff.yaml",
//  (resourceManaged in Compile in uploader).value / "media-atom-pipeline.yaml" -> "media-atom-pipeline-cloudformation/media-atom-pipeline.yaml"
)

lazy val removalLambda = (project in file("lambda/output")).settings(commonSettings, name:="ArchiveRemovedLambda")

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

unmanagedResourceDirectories in Test +=  { baseDirectory ( _ /"target/web/public/test" ).value }

