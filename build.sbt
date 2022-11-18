import BuildHelper._
import Dependencies._
import sbt.librarymanagement.ScalaArtifacts.isScala3

ThisBuild / resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

lazy val root = (project in file("."))
  .settings(stdSettings("zio-actor-root"))
  .settings(publishSetting(false))
  .aggregate(
    zioActor,
    zioActorExample,
  )

lazy val zioActor = (project in file("actor"))
  .settings(stdSettings("zio-actor"))
  .settings(publishSetting(false))
  .settings(
    libraryDependencies ++= {
      if (isScala3(scalaVersion.value)) Seq.empty
      else Seq(reflect.value % Provided)
    },
  )
  .settings(
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(`zio`, `zio-nio`, `zio-config`, `zio-config-typesafe`, `zio-test`, `zio-test-sbt`),
  )

lazy val zioActorExample = (project in file("examples"))
  .settings(stdSettings("actor-examples"))
  .settings(publishSetting(false))
  .settings(runSettings(Debug.Main))
  .settings(libraryDependencies ++= Seq())
  .dependsOn(zioActor)

