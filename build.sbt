//===========================================================================
//    Copyright 2014 Delving B.V.
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

import play.Project._

name := """narthex"""

version := "0.8.3-SNAPSHOT"

libraryDependencies ++= Seq(
  "org.webjars" % "webjars-locator" % "0.14",
  "org.webjars" %% "webjars-play" % "2.2.1-2",
  "org.webjars" % "underscorejs" % "1.6.0-3",
  "org.webjars" % "jquery" % "2.1.1",
  "org.webjars" % "bootstrap" % "3.1.1-1" exclude("org.webjars", "jquery"),
  "org.webjars" % "angularjs" % "1.3.0-beta.7-2" exclude("org.webjars", "jquery"),
  "org.webjars" % "d3js" % "3.4.11",
  "org.webjars" % "nvd3" % "1.1.15-beta-2",
  "org.webjars" % "angularjs-nvd3-directives" % "0.0.7-1",
  "org.webjars" % "angular-file-upload" % "1.6.5",
  "org.webjars" % "angular-ui-bootstrap" % "0.11.0-2",
  "org.webjars" % "ngStorage" % "0.3.0",
  "org.scalatest" % "scalatest_2.10" % "2.2.0",
  "org.scalautils" % "scalautils_2.10" % "2.1.3",
  "com.typesafe.akka" % "akka-testkit_2.10" % "2.2.0" % "test",
  "commons-io" % "commons-io" % "2.4",
  "org.basex" % "basex" % "7.9",
  "com.rockymadden.stringmetric" %% "stringmetric-core" % "0.27.2",
  cache
)

playScalaSettings

resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/repo"

//resolvers += "Delving" at "http://artifactory.delving.org/artifactory/delving"

resolvers += "Release" at "http://artifactory.delving.org/artifactory/libs-release"

resolvers += "Snapshot" at "http://artifactory.delving.org/artifactory/libs-snapshot"

requireJs += "main.js" // optimize this file and its dependencies

requireJsShim := "build.js" // http://requirejs.org/docs/optimization.html#mainConfigFile

resolvers += Resolver.file("local-ivy-repo", file(Path.userHome + "/.ivy2/local"))(Resolver.ivyStylePatterns)

publishMavenStyle := true

publishArtifact in (Compile, packageBin) := false

publishArtifact in (Compile, packageDoc) := false

publishArtifact in (Compile, packageSrc) := false

publishTo := Some("Delving" at "http://artifactory.delving.org/artifactory/delving")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
