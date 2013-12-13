libraryDependencies ++= Seq(
  "org.scala-sbt" % "ivy" % "0.13.0",
  "org.specs2" %% "specs2" % "2.2.2" % "test"
)

scalaVersion := "2.10.3"

ScalariformSupport.formatSettings

Revolver.settings


