lazy val root = project
  .in(file("."))
  .aggregate(enhancer, plugin)
  .settings(common: _*)
  .settings(noPublish: _*)
  .settings(
    name := "play-enhancer-root"
  )

lazy val enhancer = project
  .in(file("enhancer"))
  .settings(common: _*)
  .settings(publishMaven: _*)
  .settings(
    organization := "com.typesafe.play",
    name := "play-enhancer",
    libraryDependencies += "org.javassist" % "javassist" % "3.18.1-GA",
    autoScalaLibrary := false,
    crossPaths := false
  )

lazy val plugin = project
  .in(file("plugin"))
  .dependsOn(enhancer)
  .settings(common: _*)
  .settings(scriptedSettings: _*)
  .settings(publishSbtPlugin: _*)
  .settings(
    name := "sbt-play-enhancer",
    organization := "com.typesafe.sbt",
    sbtPlugin := true,
    scriptedLaunchOpts += ("-Dproject.version=" + version.value),
    scriptedLaunchOpts += "-XX:MaxPermSize=256m",
    resourceGenerators in Compile <+= generateVersionFile
  )

// Shared settings

def common = Seq(
  version := "1.0.0",
  javacOptions ++= Seq("-source", "1.6", "-target", "1.6")
)

def publishMaven = Seq(
  publishTo := {
    if (isSnapshot.value) Some(typesafeRepo("snapshots"))
    else Some(typesafeRepo("releases"))
  }
)

def typesafeRepo(repo: String) = s"typesafe $repo" at s"http://private-repo.typesafe.com/typesafe/maven-$repo"

def publishSbtPlugin = Seq(
  publishMavenStyle := false,
  publishTo := {
    if (isSnapshot.value) Some(Classpaths.sbtPluginSnapshots)
    else Some(Classpaths.sbtPluginReleases)
  }
)

def noPublish = Seq(
  publish := {},
  publishLocal := {},
  PgpKeys.publishSigned := {},
  // publish-signed needs this for some reason...
  publishTo := Some(Resolver.file("Dummy repo", target.value / "dummy-repo"))
)

def generateVersionFile = Def.task {
  val version = (Keys.version in enhancer).value
  val file = (resourceManaged in Compile).value / "play.enhancer.version.properties"
  val content = s"play.enhancer.version=$version"
  IO.write(file, content)
  Seq(file)
}
