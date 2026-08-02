
organization := "br.com.mobilemind"
version      := "0.1.0-SNAPSHOT"
scalaVersion := "3.8.4",
scalacOptions += "-experimental",


lazy val zeroalloc = project
  .in(file("zeroalloc"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "zeroalloc"
  )

lazy val tests = project
  .in(file("tests"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(zeroalloc)
  .settings(
    name := "zeroalloc-tests",
    // Framework de Testes
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.4" % Test
  )

lazy val example = project
  .in(file("example"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(zeroalloc)
  .settings(
    name := "example"
  )
