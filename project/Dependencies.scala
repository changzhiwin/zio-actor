import sbt.Keys.scalaVersion
import sbt._

object Dependencies {

  val ScalaCompactCollectionVersion = "2.8.1"
  val ZioVersion                    = "2.0.3"
  val ZioNioVersion                 = "2.0.0"
  val ZioConfigVersion              = "3.0.4"

  val `scala-compact-collection` = "org.scala-lang.modules" %% "scala-collection-compat" % ScalaCompactCollectionVersion

  val zio                   = "dev.zio" %% "zio"                 % ZioVersion
  val `zio-streams`         = "dev.zio" %% "zio-streams"         % ZioVersion
  val `zio-nio`             = "dev.zio" %% "zio-nio"             % ZioNioVersion
  val `zio-config`          = "dev.zio" %% "zio-config"          % ZioConfigVersion
  val `zio-config-typesafe` = "dev.zio" %% "zio-config-typesafe" % ZioConfigVersion
  val `zio-test`            = "dev.zio" %% "zio-test"            % ZioVersion % "test"
  val `zio-test-sbt`        = "dev.zio" %% "zio-test-sbt"        % ZioVersion % "test"

  val reflect = Def.map(scalaVersion)("org.scala-lang" % "scala-reflect" % _)
}
