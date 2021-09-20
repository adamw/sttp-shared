import com.softwaremill.SbtSoftwareMillBrowserTestJS._
import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.ossPublishSettings
import com.typesafe.tools.mima.core._

val scala2_11 = "2.11.12"
val scala2_12 = "2.12.15"
val scala2_13 = "2.13.6"
val scala2 = List(scala2_11, scala2_12, scala2_13)
val scala3 = List("3.0.2")

val sttpModelVersion = "1.4.11"

val scalaTestVersion = "3.2.10"
val zioVersion = "1.0.12"
val fs2_2_version: Option[(Long, Long)] => String = {
  case Some((2, 11)) => "2.1.0"
  case _             => "2.5.9"
}
val fs2_3_version = "3.1.2"

excludeLintKeys in Global ++= Set(ideSkipProject)

def dependenciesFor(version: String)(deps: (Option[(Long, Long)] => ModuleID)*): Seq[ModuleID] =
  deps.map(_.apply(CrossVersion.partialVersion(version)))

val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.sttp.shared",
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  ),
  mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet,
  mimaReportBinaryIssues := { if ((publish / skip).value) {} else mimaReportBinaryIssues.value },
  versionScheme := Some("semver-spec")
)

val commonJvmSettings = commonSettings ++ Seq(
  scalacOptions ++= Seq("-target:jvm-1.8"),
  ideSkipProject := (scalaVersion.value != scala2_13)
)

val commonJsSettings = commonSettings ++ Seq(
  ideSkipProject := true,
  Compile / scalacOptions ++= {
    if (isSnapshot.value) Seq.empty
    else {
      val mapSourcePrefix =
        if (ScalaArtifacts.isScala3(scalaVersion.value))
          "-scalajs-mapSourceURI"
        else
          "-P:scalajs:mapSourceURI"

      Seq {
        val dir = project.base.toURI.toString.replaceFirst("[^/]+/?$", "")
        val url = "https://raw.githubusercontent.com/softwaremill/sttp-shared"
        s"$mapSourcePrefix:$dir->$url/v${version.value}/"
      }
    }
  },
  libraryDependencies ++= Seq(
    ("org.scala-js" %%% "scalajs-dom" % "1.2.0").cross(CrossVersion.for3Use2_13)
  )
)

val commonNativeSettings = commonSettings ++ Seq(
  nativeLinkStubs := true,
  ideSkipProject := true,
  libraryDependencies ++= Seq(
    "org.scala-native" %%% "test-interface" % nativeVersion
  )
)

lazy val projectAggregates: Seq[ProjectReference] = if (sys.env.isDefinedAt("STTP_NATIVE")) {
  println("[info] STTP_NATIVE defined, including sttp-native in the aggregate projects")
  core.projectRefs ++ ws.projectRefs ++ akka.projectRefs ++ fs2ce2.projectRefs ++ fs2.projectRefs ++ monix.projectRefs ++ zio.projectRefs
} else {
  println("[info] STTP_NATIVE *not* defined, *not* including sttp-native in the aggregate projects")
  scala2.flatMap(v => List[ProjectReference](core.js(v), ws.js(v))) ++
    scala2.flatMap(v => List[ProjectReference](core.jvm(v), ws.jvm(v), fs2ce2.jvm(v), zio.jvm(v))) ++
    scala3.flatMap(v => List[ProjectReference](core.jvm(v), ws.jvm(v), fs2ce2.jvm(v), fs2.jvm(v))) ++
    scala3.flatMap(v => List[ProjectReference](core.js(v), ws.js(v))) ++
    List[ProjectReference](
      akka.jvm(scala2_12),
      akka.jvm(scala2_13),
      fs2.jvm(scala2_12),
      fs2.jvm(scala2_13),
      monix.jvm(scala2_12),
      monix.jvm(scala2_13),
      monix.js(scala2_12),
      monix.js(scala2_13),
      zio.js(scala2_12),
      zio.js(scala2_13)
    )
}

val compileAndTest = "compile->compile;test->test"

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publish / skip := true, name := "sttp-shared", scalaVersion := scala2_13)
  .aggregate(projectAggregates: _*)

lazy val core = (projectMatrix in file("core"))
  .settings(
    name := "core",
    mimaBinaryIssueFilters ++= {
      if (scalaVersion.value == scala2_11) {
        // excluding this for 2.11 as the `blocking` method will only ever be called in recompiled library code
        Seq(ProblemFilters.exclude[ReversedMissingMethodProblem]("sttp.monad.MonadError.blocking"))
      } else Nil
    }
  )
  .jvmPlatform(
    scalaVersions = scala2 ++ scala3,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = scala2 ++ scala3,
    settings = commonJsSettings ++ browserChromeTestSettings
  )
  .nativePlatform(
    scalaVersions = scala2,
    settings = commonNativeSettings
  )

lazy val ws = (projectMatrix in file("ws"))
  .settings(
    name := "ws",
    libraryDependencies += "com.softwaremill.sttp.model" %%% "core" % sttpModelVersion
  )
  .jvmPlatform(
    scalaVersions = scala2 ++ scala3,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = scala2 ++ scala3,
    settings = commonJsSettings ++ browserChromeTestSettings
  )
  .nativePlatform(
    scalaVersions = scala2,
    settings = commonNativeSettings
  )
  .dependsOn(core)

lazy val akka = (projectMatrix in file("akka"))
  .settings(
    name := "akka"
  )
  .jvmPlatform(
    scalaVersions = List(scala2_12, scala2_13),
    settings = commonJvmSettings ++ Seq(
      libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.16" % "provided"
    )
  )
  .dependsOn(core)

lazy val fs2ce2 = (projectMatrix in file("fs2-ce2"))
  .settings(
    name := "fs2-ce2",
    libraryDependencies ++= dependenciesFor(scalaVersion.value)(
      "co.fs2" %% "fs2-io" % fs2_2_version(_)
    )
  )
  .jvmPlatform(
    scalaVersions = scala2 ++ scala3,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = scala2 ++ scala3,
    settings = commonJsSettings ++ browserChromeTestSettings
  )
  .dependsOn(core)

lazy val fs2 = (projectMatrix in file("fs2"))
  .settings(
    name := "fs2",
    libraryDependencies += "co.fs2" %% "fs2-io" % fs2_3_version
  )
  .jvmPlatform(
    scalaVersions = List(scala2_12, scala2_13) ++ scala3,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = List(scala2_12, scala2_13) ++ scala3,
    settings = commonJsSettings ++ browserChromeTestSettings
  )
  .dependsOn(core)

lazy val monix = (projectMatrix in file("monix"))
  .settings(
    name := "monix",
    libraryDependencies += "io.monix" %%% "monix" % "3.4.0"
  )
  .jvmPlatform(
    scalaVersions = List(scala2_12, scala2_13) ++ scala3,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = List(scala2_12, scala2_13) ++ scala3,
    settings = commonJsSettings ++ browserChromeTestSettings
  )
  .dependsOn(core)

lazy val zio = (projectMatrix in file("zio"))
  .settings(
    name := "zio",
    libraryDependencies ++= Seq("dev.zio" %% "zio-streams" % zioVersion, "dev.zio" %% "zio" % zioVersion)
  )
  .jvmPlatform(
    scalaVersions = scala2 ++ scala3,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = List(scala2_12, scala2_13) ++ scala3,
    settings = commonJsSettings ++ browserChromeTestSettings
  )
  .dependsOn(core)
