// Resolve scala-xml version conflict for sbt 1.9.x / Java 21
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.20")
addSbtPlugin("org.foundweekends.giter8" % "sbt-giter8-scaffold" % "0.13.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.2")
addSbtPlugin("com.typesafe.sbt"  % "sbt-less" % "1.1.2")
addSbtPlugin("com.typesafe.sbt"  % "sbt-rjs" % "1.0.10")
addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.1.4")
