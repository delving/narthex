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

val versionString = "0.6.0"

version := versionString

libraryDependencies ++= Seq(
  "org.webjars" % "webjars-locator" % "0.14",
  "org.webjars" %% "webjars-play" % "2.2.1-2",
  "org.webjars" % "underscorejs" % "1.6.0-3",
  "org.webjars" % "jquery" % "2.1.1",
  "org.webjars" % "bootstrap" % "3.1.1-1" exclude("org.webjars", "jquery"),
  "org.webjars" % "angularjs" % "1.3.0-beta.7-2" exclude("org.webjars", "jquery"),
  "org.webjars" % "d3js" % "3.4.6-1",
  "org.webjars" % "nvd3" % "1.1.15-beta-2",
  "org.webjars" % "angularjs-nvd3-directives" % "0.0.7-1",
  "org.webjars" % "angular-file-upload" % "1.3.1-1",
  "org.webjars" % "angular-ui-bootstrap" % "0.11.0-2",
  "org.mindrot" % "jbcrypt" % "0.3m",
  "org.scalatest" % "scalatest_2.10" % "2.1.3" % "test",
  "org.scalautils" % "scalautils_2.10" % "2.1.3",
  "commons-io" % "commons-io" % "2.4",
  "org.basex" % "basex" % "7.3.1",
//  "org.webjars" % "cryptojs" % "3.1.2",
//  "org.reactivemongo" %% "reactivemongo" % "0.10.0",
  cache
)

playScalaSettings

resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/repo"

resolvers += "Delving Proxy repository" at "http://nexus.delving.org/nexus/content/groups/public/"

// This tells Play to optimize this file and its dependencies
requireJs += "main.js"

// The main config file
// See http://requirejs.org/docs/optimization.html#mainConfigFile
requireJsShim := "build.js"

// To completely override the optimization process, use this config option:
//requireNativePath := Some("node r.js -o name=main out=javascript-min/main.min.js")

resolvers += Resolver.file("local-ivy-repo", file(Path.userHome + "/.ivy2/local"))(Resolver.ivyStylePatterns)

publishMavenStyle := true

publishArtifact in (Compile, packageDoc) := false

publishArtifact in (Compile, packageSrc) := false

publishTo := {
  val nexus = "http://nexus.delving.org/nexus/content/repositories/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "snapshots")
  else
    Some("releases"  at nexus + "releases")
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
