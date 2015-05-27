lazy val `play-enhancer-root` = project
  .in(file("."))
  .enablePlugins(PlayRootProject)
  .aggregate(`play-enhancer`, `sbt-play-enhancer`)

lazy val `play-enhancer` = project
  .in(file("enhancer"))
  .enablePlugins(PlaySbtLibrary)
  .settings(
    libraryDependencies += "org.javassist" % "javassist" % "3.18.2-GA",
    autoScalaLibrary := false,
    crossPaths := false
  )

lazy val `sbt-play-enhancer` = project
  .in(file("plugin"))
  .enablePlugins(PlaySbtPlugin)
  .dependsOn(`play-enhancer`)
  .settings(
    organization := "com.typesafe.sbt",
    resourceGenerators in Compile <+= generateVersionFile
  )

playBuildRepoName in ThisBuild := "play-enhancer"

def generateVersionFile = Def.task {
  val version = (Keys.version in `play-enhancer`).value
  val file = (resourceManaged in Compile).value / "play.enhancer.version.properties"
  val content = s"play.enhancer.version=$version"
  IO.write(file, content)
  Seq(file)
}

