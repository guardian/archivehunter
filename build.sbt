import sbt._
import Keys._

enablePlugins(RiffRaffArtifact, JDebPackaging)

libraryDependencies += "org.vafer" % "jdeb" % "1.3" artifacts (Artifact("jdeb", "jar", "jar"))

lazy val commonSettings = Seq(
  name := "ArchiveHunter",
  version := "1.0",
  scalaVersion := "2.12.2",
  libraryDependencies := Seq("org.apache.logging.log4j" % "log4j-core" % "2.8.2",
    "org.apache.logging.log4j" % "log4j-api" % "2.8.2",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
    "com.amazonaws" % "aws-java-sdk-s3" % "1.11.346",
    specs2 % Test)
)

scalaVersion := "2.12.2"

lazy val `archivehunter` = (project in file("."))
  .enablePlugins(PlayScala)
  .aggregate(inputLambda,removalLambda,common)
  .settings(commonSettings,
    name:="ArchiveHunterApp",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
      jdbc, ehcache, ws)
  )

val elastic4sVersion = "6.0.4"
val awsversion = "2.0.0-preview-10"

val lambdaDeps = Seq(
  "com.amazonaws" % "aws-lambda-java-log4j2" % "1.0.0"
)
val circeVersion = "0.9.3"

lazy val common = (project in file("common"))
  .settings(commonSettings,
    name:="ArchiveHunterCommon",
    libraryDependencies ++= Seq(
      "com.sksamuel.elastic4s" %% "elastic4s-http" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-circe" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % "test",
      "com.sksamuel.elastic4s" %% "elastic4s-embedded" % elastic4sVersion % "test",
      "com.amazonaws" % "aws-java-sdk-s3" % "1.11.346",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-java8" % circeVersion
    )
  )

lazy val inputLambda = (project in file("lambda/input"))
  .dependsOn(common)
  .settings(commonSettings,
  name:="ArchiveImportLambda",
  // https://mvnrepository.com/artifact/com.amazonaws/aws-java-sdk-lambda
  libraryDependencies ++= Seq(
    "com.amazonaws" % "aws-java-sdk-lambda" % "1.11.346",
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
  (packageBin in Debian in archivehunter).value -> s"${(name in archivehunter).value}/${(name in archivehunter).value}.deb",
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

