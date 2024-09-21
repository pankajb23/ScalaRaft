ThisBuild / scalaVersion := "2.13.14"

ThisBuild / version := "1.0-SNAPSHOT"

scalafmtOnCompile := true

fork in run := true

javaOptions in Universal ++= Seq(
  "-Dlogback.debug=true",
  "-Dlogger.file=logback.xml"
)

lazy val common = (project in file("common"))
  .enablePlugins(PlayScala)
  .settings(
    name := """common""",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    )
  )

lazy val lsm = (project in file("lsm"))
  .enablePlugins(PlayScala)
  .settings(
    name := """lsm""",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    libraryDependencies ++= (common / libraryDependencies).value
  )
  .dependsOn(common % "compile->compile;test->test")

lazy val raft = (project in file("raft"))
  .enablePlugins(PlayScala)
  .settings(
    name := """raft""",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    Compile / resourceDirectory := baseDirectory.value / "src" / "main" / "resources",
    libraryDependencies ++= (common / libraryDependencies).value
  )
  .dependsOn(common % "compile->compile;test->test", lsm % "compile->compile;test->test")

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := """DSoftware""",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    Test / scalaSource := baseDirectory.value / "src" / "main" / "test",
    Compile / resourceDirectory := baseDirectory.value / "src" / "main" / "resources",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    )
  )
  .dependsOn(
    common % "compile->compile;test->test",
    lsm % "compile->compile;test->test",
    raft % "compile->compile;test->test"
  )
  .aggregate(common, lsm, raft)
