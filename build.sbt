//===========================================================================
//    Copyright 2014, 2015, 2016, 2023 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

name := "narthex"
organization := "eu.delving"
maintainer := "info@delving.eu"

lazy val root = (project in file(".")).
  enablePlugins(PlayScala).
  enablePlugins(BuildInfoPlugin). // See: https://github.com/sbt/sbt-buildinfo
  enablePlugins(SbtWeb).
  enablePlugins(DockerPlugin).
  //enablePlugins(GitVersioning). // Not working, see: https://github.com/rallyhealth/sbt-git-versioning
  settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "buildinfo"
  )

scalaVersion := "2.13.11"

libraryDependencies ++= Seq(
  guice,
  ws,
  ehcache
)

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-library" % "2.13.11",
  "org.scala-lang.modules" %% "scala-xml" % "1.3.0",
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.21",
  "com.typesafe.akka" %% "akka-protobuf-v3" % "2.6.21",
  "com.typesafe.akka" %% "akka-stream" % "2.6.21",
  "com.typesafe.akka" %% "akka-serialization-jackson" % "2.6.21",
  "com.typesafe.play" %% "play-mailer" % "8.0.1",
  "com.typesafe.play" %% "play-mailer-guice" % "8.0.1",
)

libraryDependencies ++= Seq(
  "commons-io" % "commons-io" % "2.13.0",
  "org.apache.jena" % "jena-arq" % "3.1.0" exclude("log4j", "log4j"),
  "org.apache.poi" % "poi" % "5.2.3",
  "org.apache.poi" % "poi-ooxml" % "5.2.3",
  "org.apache.commons" % "commons-csv" % "1.10.0",
  "com.softwaremill.retry" %% "retry" % "0.3.6",
  "org.asynchttpclient" % "async-http-client" % "2.12.3",
  "com.codahale.metrics" % "metrics-core" % "3.0.2",
  "com.codahale.metrics" % "metrics-healthchecks" % "3.0.2",
  "com.kenshoo" %% "metrics-play" % "2.7.3_0.8.2",
  "nl.grons" %% "metrics4-scala" % "4.2.9",
  "org.coursera" % "metrics-datadog" % "1.1.14",
  //"com.rockymadden.stringmetric" % "stringmetric-core" % "0.26.1",
  "info.debatty" % "java-string-similarity" % "2.0.0", // Replaces stringmetric-core
)

libraryDependencies ++= Seq(
  "org.webjars" %% "webjars-play" % "2.8.18",
  "org.webjars" % "jquery" % "2.1.1",
  "org.webjars" % "bootstrap" % "3.1.1",
  "org.webjars" % "underscorejs" % "1.8.3",
  "org.webjars" % "jquery" % "2.1.1",
  "org.webjars" % "angularjs" % "1.3.17",
  "org.webjars" % "angular-file-upload" % "2.0.5",
  "org.webjars" % "angular-ui-bootstrap" % "0.11.2",
  "org.webjars" % "ng-grid" % "2.0.13",
  "org.webjars" % "ngStorage" % "0.3.0",
  "org.webjars" % "angular-sanitize" % "1.3.11",
)

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.1.4" % Test,
  "org.scalatestplus" %% "mockito-3-4" % "3.2.10.0" % Test,
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test,
  "de.leanovate.play-mockws" %% "play-mockws" % "2.8.1" % Test,
)

resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository"
libraryDependencies += "eu.delving" % "sip-core" % "1.3.0" exclude("org.apache.jena", "jena-arq")

dependencyOverrides ++= Seq(
  // Currently necessary for running on JDK 17
  // See: https://github.com/playframework/playframework/releases/2.8.15
  "com.google.inject" % "guice" % "5.1.0",
  "com.google.inject.extensions" % "guice-assistedinject" % "5.1.0",
  // com.kenshoo:metrics-play brings in too new Jackson Databind
  // Akka 2.6 currently depends on Jackson 2.11.4 so use that
  "com.fasterxml.jackson.core" % "jackson-core" % "2.11.4",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.11.4",
  "com.fasterxml.jackson.core" % "jackson-annotations" % "2.11.4",
)

routesGenerator := InjectedRoutesGenerator

pipelineStages := Seq(rjs)
RjsKeys.paths += ("jsRoutes" -> ("/narthex/jsRoutes" -> "empty:"))
RjsKeys.optimize := "none"

import com.typesafe.sbt.packager.docker._
dockerCommands := Seq(
  Cmd("FROM", "frolvlad/alpine-oraclejdk8:slim"),
  Cmd("MAINTAINER", "info@delving.eu"),
  Cmd("RUN", "apk update && apk add bash"),
  Cmd("WORKDIR", "/opt/docker"),
  Cmd("ADD", "opt /opt"),
  Cmd("RUN", "chown -R daemon:daemon ."),
  Cmd("EXPOSE", "9000"),
  Cmd("USER", "daemon"),
  ExecCmd("ENTRYPOINT", "bin/narthex", "-Dconfig.file=/opt/docker/conf/overrides.conf", "-Dlogger.file=/opt/docker/conf/logback.xml")
)

// Scala Compiler Options
scalacOptions ++= Seq(
  "-release", "8",
  "-encoding", "UTF-8",
  "-deprecation", // warning and location for usages of deprecated APIs
  "-feature", // warning and location for usages of features that should be imported explicitly
  "-unchecked", // additional warnings where generated code depends on assumptions
  "-Xlint", // recommended additional warnings
  "-Xcheckinit", // runtime error when a val is not initialized due to trait hierarchies (instead of NPE somewhere else)
  //"-Ywarn-adapted-args", // Warn if an argument list is modified to match the receiver
  //"-Yno-adapted-args", // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver
  "-Ywarn-value-discard", // Warn when non-Unit expression results are unused
  //"-Ywarn-inaccessible", // Warn about inaccessible types in method signatures
  "-Ywarn-dead-code", // Warn when dead code is identified
  "-Ywarn-unused", // Warn when local and private vals, vars, defs, and types are unused
  "-Ywarn-numeric-widen" // Warn when numerics are widened
)
