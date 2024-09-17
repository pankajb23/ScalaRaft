ThisBuild / scalaVersion := "2.13.14"

ThisBuild / version := "1.0-SNAPSHOT"


lazy val common = (project in file("common"))
  .enablePlugins(PlayScala)
  .settings(
    name := """common""",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    Compile / resourceDirectory := baseDirectory.value / "src" / "main" / "resources",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    )
  )

lazy val raft = (project in file("raft"))
  .enablePlugins(PlayScala)
  .settings(
    name := """raft""",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    Compile / resourceDirectory := baseDirectory.value / "src" / "main" / "resources",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    )
  ).dependsOn(common % "compile->compile;test->test")

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := """DSoftware""",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    )
  ).aggregate(common, raft)
