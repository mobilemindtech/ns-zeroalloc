
organization := "br.com.mobilemind"
version      := "0.1.0-SNAPSHOT"
scalaVersion := "3.8.4"


lazy val zeroalloc = project
  .in(file("zeroalloc"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "zeroalloc"
  )

// Compiler plugin that does the actual checking. Runs inside the compiler (JVM),
// so it is a plain JVM project published against the full Scala version.
lazy val plugin = project
  .in(file("plugin"))
  .settings(
    name := "zeroalloc-plugin",
    crossVersion := CrossVersion.full,
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Provided,
      "org.scalameta"  %% "munit"           % "1.3.4"            % Test
    ),
    // The tests compile small snippets exactly like a Scala Native build does:
    // against the Native classpath, with nscplugin and this plugin enabled.
    Test / fork := true,
    Test / javaOptions ++= {
      val conv = fileConverter.value
      val nativeCp = (zeroalloc / Compile / fullClasspath).value.map(a => conv.toPath(a.data))
      val nscplugin = (zeroalloc / update).value.matching(moduleFilter(name = "nscplugin*"))
      Seq(
        s"-Dzeroalloc.test.classpath=${nativeCp.mkString(java.io.File.pathSeparator)}",
        s"-Dzeroalloc.test.plugins=${(nscplugin :+ conv.toPath((Compile / packageBin).value)).mkString(",")}"
      )
    }
  )

lazy val tests = project
  .in(file("tests"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(zeroalloc)
  .settings(
    name := "zeroalloc-tests",
    scalacOptions += s"-Xplugin:${fileConverter.value.toPath((plugin / Compile / packageBin).value)}",
    // Framework de Testes
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.4" % Test
  )

// Links in release mode a program made of methods the plugin accepts, so that
// scripts/ir-allocs.py can check the generated IR. See docs/allowlist.md.
lazy val conformance = project
  .in(file("conformance"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(zeroalloc)
  .settings(
    name := "zeroalloc-conformance",
    publish / skip := true,
    scalacOptions ++= Seq(
      s"-Xplugin:${fileConverter.value.toPath((plugin / Compile / packageBin).value)}",
      "-P:zeroalloc:allow-throw"
    ),
    nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast))
  )
