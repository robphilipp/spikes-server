lazy val akkaHttpVersion = "10.1.8"
lazy val akkaVersion = "2.5.23"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.12.8"
    )),
    name := "spikes-server",
    libraryDependencies ++= Seq(
      // akka dependencies
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,

      // logging
//      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
//      "ch.qos.logback" % "logback-classic" % "1.2.3",

      // kafka
      "com.typesafe.akka" %% "akka-stream-kafka" % "1.0.4",

      // core library for building spikes networks
      "com.digitalcipher.spiked" %% "spikes-core" % "0.0.23-snapshot",

      // json serialization/deserialization
      "io.spray" %% "spray-json" % "1.3.5",

      // testing
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
      "org.scalatest" %% "scalatest" % "3.0.5" % Test,
    )
  )
