import bintray.Keys._

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
    libraryDependencies += "org.javassist" % "javassist" % "3.18.2-GA",
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

def common = releaseCommonSettings ++ Seq(
  javacOptions in compile ++= Seq("-source", "1.6", "-target", "1.6"),
  homepage := Some(url("https://github.com/playframework/play-enhancer")),
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  pomExtra :=
    <scm>
      <url>git@github.com:playframework/play-enhancer.git</url>
      <connection>scm:git:git@github.com:playframework/play-enhancer.git</connection>
    </scm>
    <developers>
      <developer>
        <id>jroper</id>
        <name>James Roper</name>
        <url>https://jazzy.id.au</url>
      </developer>
    </developers>
)

def publishMaven = Seq(
  publishTo := {
    if (isSnapshot.value) Some(Opts.resolver.sonatypeSnapshots)
    else Some(Opts.resolver.sonatypeStaging)
  }
)

def publishSbtPlugin = bintrayPublishSettings ++ Seq(
  publishMavenStyle := false,
  bintrayOrganization in bintray := Some("playframework"),
  repository in bintray := "sbt-plugin-releases"
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

// Release settings

lazy val scriptedTask = TaskKey[Unit]("scripted-task")

def releaseCommonSettings: Seq[Setting[_]] = releaseSettings ++ {
  import sbtrelease._
  import ReleaseStateTransformations._
  import ReleaseKeys._

  def runScriptedTest = ReleaseStep(
    action = releaseTask(scriptedTask in plugin)
  )

  Seq(
    crossBuild := true,
    publishArtifactsAction := PgpKeys.publishSigned.value,
    tagName := (version in ThisBuild).value,

    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      runScriptedTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )
}
