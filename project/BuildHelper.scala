import sbt.Keys._
import sbt._
import scalafix.sbt.ScalafixPlugin.autoImport._
import xerial.sbt.Sonatype.autoImport._

object BuildHelper extends ScalaSettings {
  val Scala212         = "2.12.16"
  val Scala213         = "2.13.8"
  val ScalaDotty       = "3.2.0"
  val ScoverageVersion = "1.9.3"
  val JmhVersion       = "0.4.3"

  private val stdOptions = Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked",
    "-language:postfixOps"
  ) ++ {
    if (sys.env.contains("CI")) {
      Seq("-Xfatal-warnings")
    } else {
      Nil // to enable Scalafix locally
    }
  }

  def extraOptions(scalaVersion: String) =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((3, 0))  => scala3Settings
      case Some((2, 12)) => scala212Settings
      case Some((2, 13)) => scala213Settings
      case _             => Seq.empty
    }

  def publishSetting(publishArtifacts: Boolean) = {
    val publishSettings = Seq(
      organization           := "dev.zio",
      organizationName       := "zio",
      licenses := Seq("MIT License" -> url("https://github.com/changzhiwin/zio-actor/blob/master/LICENSE")),
      sonatypeCredentialHost := "oss.sonatype.org",
      sonatypeRepository     := "https://oss.sonatype.org/service/local",
      sonatypeProfileName    := "dev.zio",
      publishTo := sonatypePublishToBundle.value,
      sonatypeTimeoutMillis := 300 * 60 * 1000,
      publishMavenStyle := true,
      credentials ++=
        (for {
          username <- Option(System.getenv().get("SONATYPE_USERNAME"))
          password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
        } yield Credentials(
          "Sonatype Nexus Repository Manager",
          "oss.sonatype.org",
          username,
          password
        )).toSeq
    )
    val skipSettings    = Seq(
      publish / skip  := true,
      publishArtifact := false
    )
    if (publishArtifacts) publishSettings else publishSettings ++ skipSettings
  }

  def stdSettings(prjName: String) = Seq(
    name                                   := s"$prjName",
    ThisBuild / crossScalaVersions         := Seq(Scala212, Scala213, ScalaDotty),
    ThisBuild / scalaVersion               := Scala213,
    scalacOptions                          := stdOptions ++ extraOptions(scalaVersion.value),
    semanticdbEnabled                      := true,
    semanticdbVersion                      := scalafixSemanticdb.revision, // use Scalafix compatible version
    ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value),
    ThisBuild / scalafixDependencies ++=
      List(
        "com.github.liancheng" %% "organize-imports" % "0.6.0",
        "com.github.vovapolu"  %% "scaluzzi"         % "0.1.23",
      ),
    Test / parallelExecution               := true,
    incOptions ~= (_.withLogRecompileOnMacro(false)),
    autoAPIMappings                        := true,
    ThisBuild / javaOptions                := Seq(
      //"-Dio.netty.leakDetectionLevel=paranoid",
      //s"-DZIOActorLogLevel=${Debug.ZIOActorLogLevel}",
    ),
    ThisBuild / fork                       := true
  )

  def runSettings(className: String = "examples.HelloActor") = Seq(
    fork                      := true,
    Compile / run / mainClass := Option(className)
  )

  def meta = Seq(
    ThisBuild / homepage   := Some(url("https://github.com/changzhiwin/zio-actor")),
    ThisBuild / scmInfo    :=
      Some(
        ScmInfo(url("https://github.com/changzhiwin/zio-actor"), "scm:git@github.com:changzhiwin/zio-actor.git"),
      ),
    ThisBuild / developers := List(
      Developer(
        "changzhiwin",
        "Zhi Chang",
        "changzhwin@gmail.com",
        new URL("https://github.com/changzhiwin"),
      )
    ),
  )
}
