import play.Project._

name := """xml-ray"""

version := "0.5"

libraryDependencies ++= Seq(
  // WebJars infrastructure
  "org.webjars" % "webjars-locator" % "0.13",
  "org.webjars" %% "webjars-play" % "2.2.1-2",
  // WebJars dependencies
  "org.webjars" % "underscorejs" % "1.6.0-1",
  "org.webjars" % "d3js" % "3.4.6",
  "org.webjars" % "jquery" % "1.11.0-1",
  "org.webjars" % "bootstrap" % "3.1.1" exclude("org.webjars", "jquery"),
  "org.webjars" % "angularjs" % "1.2.14" exclude("org.webjars", "jquery"),
  "org.webjars" % "angular-file-upload" % "1.2.8-1",
  "org.mindrot" % "jbcrypt" % "0.3m",
  "org.scalatest" % "scalatest_2.10" % "2.1.3" % "test",
  "org.scalautils" % "scalautils_2.10" % "2.1.3",
  "commons-io" % "commons-io" % "2.4",
  // use cache
  cache
)

playScalaSettings

resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/repo"

// This tells Play to optimize this file and its dependencies
requireJs += "main.js"

// The main config file
// See http://requirejs.org/docs/optimization.html#mainConfigFile
requireJsShim := "build.js"

// To completely override the optimization process, use this config option:
//requireNativePath := Some("node r.js -o name=main out=javascript-min/main.min.js")
