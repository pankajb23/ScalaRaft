ThisBuild / scalaVersion := "2.13.14"

ThisBuild / version := "1.0-SNAPSHOT"

val akkaVersion = "2.8.0"
val akkaHttpVersion = "10.5.0"
val log4jVersion = "2.20.0"
val sttpVersion = "3.8.15"  // Add this line
val enumeratumVersion = "1.7.2"  // Add this line


libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "org.apache.logging.log4j" % "log4j-api" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4jVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "com.typesafe.play" %% "play" % "2.9.0",
  "com.softwaremill.sttp.client3" %% "core" % sttpVersion,  // Add this line
  "com.softwaremill.sttp.client3" %% "akka-http-backend" % sttpVersion,  // Add this line
  "com.beachape" %% "enumeratum" % enumeratumVersion,  // Add this line
  "com.beachape" %% "enumeratum-play-json" % enumeratumVersion,  // Add this line
  "com.typesafe.play" %% "play-json" % "2.9.4",  // Add this line if not already included
)