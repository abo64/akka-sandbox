name := """akka-sandbox"""

version := "1.0"

scalaVersion := "2.11.5"

val akkaVersion = "2.3.9"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "org.scalatest" %% "scalatest" % "2.2.3" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.1" % "test",
  "junit" % "junit" % "4.12" % "test"
//  "com.novocode" % "junit-interface" % "0.10" % "test"
)

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")

EclipseKeys.withSource := true
